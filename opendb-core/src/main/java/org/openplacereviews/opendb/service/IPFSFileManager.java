package org.openplacereviews.opendb.service;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.dto.IpfsStatusDTO;
import org.openplacereviews.opendb.dto.ResourceDTO;
import org.openplacereviews.opendb.ops.OpObject;
import org.openplacereviews.opendb.ops.OpOperation;
import org.openplacereviews.opendb.util.OUtils;
import org.openplacereviews.opendb.util.exception.TechnicalException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import net.jodah.failsafe.FailsafeException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class IPFSFileManager {

	protected static final Log LOGGER = LogFactory.getLog(IPFSFileManager.class);

	private static final int SPLIT_FOLDERS_DEPTH = 3;
	private static final int FOLDER_LENGTH = 4;

	@Value("${opendb.storage.local-storage:}")
	private String directory;
	
	@Value("${opendb.storage.timeToStoreUnusedSec:86400}")
	private int timeToStoreUnusedObjectsSeconds;
	
	private File folder;

	@Autowired
	private DBConsensusManager dbManager;
	
	@Autowired
	private IPFSService ipfsService;

	public void init() {
		try {
			if(!OUtils.isEmpty(directory)) {
				folder = new File(directory);
				folder.mkdirs();
				ipfsService.connect();
				LOGGER.info(String.format("Init directory to store external images at %s", folder.getAbsolutePath()));
			}
		} catch (IOException e) {
			e.printStackTrace();
			LOGGER.error("IPFS directory for images was not created");
		}
	}
	
	public boolean isRunning() {
		return folder != null;
	}
	
	public boolean isIPFSRunning() {
		return ipfsService.isRunning();
	}
	
	public ResourceDTO addFile(ResourceDTO resourceDTO) throws IOException {
		// TODO calculate Hash of local file (sha256? or md5?)
		resourceDTO.calculateHash();
		if(ipfsService.isRunning()) {
			resourceDTO.setCid(ipfsService.writeContent(resourceDTO.getMultipartFile().getBytes()));
		}
		File f = getFileByHash(resourceDTO.getHash(), resourceDTO.getExtension());
		f.getParentFile().mkdirs();
		FileUtils.writeByteArrayToFile(f, resourceDTO.getMultipartFile().getBytes());
		dbManager.storeResourceObject(resourceDTO);
		return resourceDTO;
	}


	public File getFileByHash(String hash, String extension) {
		// TODO optionally if extension = null, scan folder to find proper extension
		return getFileByHashImpl(hash, extension);
	}
	


	public IpfsStatusDTO checkingMissingImagesInIPFS() {
		List<String> pinnedImagesOnIPFS = ipfsService.getPinnedResources();
		List<String> activeObjects = dbManager.loadImageObjectsByActiveStatus(true);

		AtomicBoolean status = new AtomicBoolean(true);
		activeObjects.parallelStream().forEach(cid -> {
			if (!pinnedImagesOnIPFS.contains(cid)) {
				status.set(false);
			}
		});

		return IpfsStatusDTO.getMissingImageStatus(activeObjects.size() + "/" + pinnedImagesOnIPFS.size(), status.get() ? "OK" : "NOT OK");
	}

	public IpfsStatusDTO uploadMissingResourcesToIPFS() {
		List<String> pinnedImagesOnIPFS = ipfsService.getTrackedResources();
		List<String> activeObjects = dbManager.loadImageObjectsByActiveStatus(true);

		LOGGER.debug("Start pinning missing images");
		activeObjects.forEach(cid -> {
			LOGGER.debug("Loadded CID: " + cid);
			if (!pinnedImagesOnIPFS.contains(cid)) {
				try {
					LOGGER.debug("Start reading/upload image from/to node for cid: " + cid);
					OutputStream os = read(cid);
					if (!os.toString().isEmpty()) {
						LOGGER.debug("Start pin cid: " + cid);
						pin(cid);
					}
				} catch (Exception e) {
					LOGGER.error(e.getMessage(), e);
				}
			}
		});

		return checkingMissingImagesInIPFS();
	}


	public IpfsStatusDTO removeUnusedImageObjectsFromSystemAndUnpinningThem() throws IOException {
		List<ResourceDTO> notActiveImageObjects = dbManager.loadUnusedImageObject(timeToStoreUnusedObjectsSeconds);
		notActiveImageObjects.parallelStream().forEach(res -> {
			removeImageObject(res);
		});
		ipfsService.clearNotPinnedImagesFromIPFSLocalStorage();
		return statusImagesInDB();
	}
	
	private void removeImageObject(ResourceDTO resourceDTO) {
		ipfsService.unpin(resourceDTO.getCid());
		dbManager.removeResObjectFromDB(resourceDTO);
		File file = getFileByHash(resourceDTO.getHash(), resourceDTO.getExtension());
		if (file.delete()) {
			LOGGER.info(String.format("File %s is deleted", file.getName()));
		} else {
			LOGGER.error(String.format("Deleteing %s has failed", file.getAbsolutePath()));
		}
	}

	public IpfsStatusDTO statusImagesInDB() {
		// TODO display count active objects, pending objects (objects to gc)
		List<ResourceDTO> notActiveImageObjects = dbManager.loadUnusedImageObject(timeToStoreUnusedObjectsSeconds);
		return IpfsStatusDTO.getMissingImageStatus(String.valueOf(notActiveImageObjects.size()), notActiveImageObjects.size() == 0 ? "OK" : "Images can be removed");
	}
	
	public void processOperations(List<OpOperation> candidates) {
		// TODO mark operation hash where image was used and store in db !
		List<ResourceDTO> array = new ArrayList<ResourceDTO>();
		candidates.forEach(operation -> {
			List<OpObject> nw = operation.getNew();
			for(OpObject o : nw) {
				array.clear();
				getImageObject(o.getRawOtherFields(), array);
				array.forEach ( resDTO -> {
					resDTO.setOpHash(operation.getRawHash());
					dbManager.updateImageActiveStatus(resDTO, true);
					ipfsService.pin(resDTO.getCid());
				});
			}
		});		
	}
	
	@SuppressWarnings("unchecked")
	private void getImageObjectFromList(List<?> l, List<ResourceDTO> array) {
		for(Object o : l ) {
			if(o instanceof Map) {
				getImageObject((Map<String, ?>) o, array);
			}
			if(o instanceof List) {
				getImageObjectFromList((List<?>) o, array);
			}
		}
	}
	
	@SuppressWarnings("unchecked")
	private void getImageObject(Map<String, ?> map, List<ResourceDTO> array) {
		if (map.containsKey(OpOperation.F_TYPE) && map.get(OpOperation.F_TYPE).equals("#image")) {
			array.add(ResourceDTO.of(map.get("hash").toString(), map.get("extension").toString(), map.get("cid").toString()));
		} else {
			map.entrySet().forEach(e -> {
				if (e.getValue() instanceof Map) {
					getImageObject( (Map<String, ?>) e.getValue(), array);
				}
				if (e.getValue() instanceof List) {
					getImageObjectFromList( (List<?>) e.getValue(), array);
				}
			});
		}
	}

	private File getFileByHashImpl(String hash, String extension){
		String fname = generateFileName(hash, extension);
		File file = new File(folder, fname);
		file.getParentFile().mkdirs();
		return file;
	}


	private String generateFileName(String hash, String ext) {
		StringBuilder fPath = new StringBuilder();
		int splitF = SPLIT_FOLDERS_DEPTH;
		while(splitF > 0 && hash.length() > FOLDER_LENGTH) {
			fPath.append(hash.substring(0, FOLDER_LENGTH)).append("/");
			hash = hash.substring(FOLDER_LENGTH);
			splitF--;
		}
		fPath.append(hash).append(".").append(ext);
		return fPath.toString();
	}


}
