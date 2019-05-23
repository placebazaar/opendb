package org.openplacereviews.opendb.service.ipfs;

import com.google.common.collect.Sets;
import com.google.gson.Gson;
import com.mashape.unirest.http.HttpResponse;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.ipfs.multihash.Multihash;
import net.jodah.failsafe.Failsafe;
import net.jodah.failsafe.FailsafeException;
import net.jodah.failsafe.RetryPolicy;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openplacereviews.opendb.service.DBConsensusManager;
import org.openplacereviews.opendb.service.ipfs.dto.ImageDTO;
import org.openplacereviews.opendb.service.ipfs.dto.IpfsStatusDTO;
import org.openplacereviews.opendb.service.ipfs.pinning.IPFSClusterPinningService;
import org.openplacereviews.opendb.service.ipfs.pinning.IPFSFileManager;
import org.openplacereviews.opendb.service.ipfs.pinning.PinningService;
import org.openplacereviews.opendb.service.ipfs.pinning.StorageService;
import org.openplacereviews.opendb.util.exception.ConnectionException;
import org.openplacereviews.opendb.util.exception.TechnicalException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.openplacereviews.opendb.service.ipfs.pinning.IPFSClusterPinningService.BASE_URI;

@Service
public class IPFSService implements StorageService, PinningService {

	protected static final Log LOGGER = LogFactory.getLog(IPFSService.class);

	@Value("${ipfs.host:localhost}")
	public String ipfsHost;

	@Value("${ipfs.port:5001}")
	public int ipfsPort;

	@Value("${ipfs.directory:/opendb/storage/}")
	public String ipfsDirectory;

	@Value("${ipfs.timeout:10000}")
	public int ipfsTimeout;

	@Value("${ipfs.storing.time:86400}")
	public int timeStoringUnusedObjects;

	@Autowired
	private DBConsensusManager dbConsensusManager;

	@Autowired
	private IPFSFileManager ipfsFileManager;

	private IPFS ipfs;
	private ExecutorService pool;
	private RetryPolicy<Object> retryPolicy;
	private Set<PinningService> replicaSet;

	public  IPFSService() {
	}

	private void generateInstance(IPFSService ipfsService, IPFS ipfs) {
		ipfsService.ipfs = ipfs;
		ipfsService.replicaSet = Sets.newHashSet(IPFSClusterPinningService.connect(ipfs.host, ipfs.port)); // IPFSService is a PinningService
		ipfsService.configureThreadPool(10);
		ipfsService.configureRetry(3);
		ipfsFileManager.init();
	}

	public void connect() {
		connect(ipfsHost, ipfsPort);
	}

	public void connect(String host, Integer port) {
		connect(host, port, null);
	}

	public void connect(String multiaddress) {
		IPFSFileManager ipfsFileManager = new IPFSFileManager();
		ipfsFileManager.init();

		connect(null, null, multiaddress);
	}

	private void connect(String host, Integer port, String multiaddress) {
		try {
			IPFS ipfs = Optional.ofNullable(multiaddress).map(IPFS::new).orElseGet(() -> new IPFS(host, port));
			LOGGER.info(String.format("Connected to ipfs [host: %s, port: %d, multiaddress: %s]: Node v.%s", host, port, multiaddress,
					ipfs.version()));

			generateInstance(this, ipfs);

		} catch (Exception ex) {
			String msg = String.format("Error whilst connecting to IPFS [host: %s, port: %s, multiaddress: %s]", host,
					port, multiaddress);

			LOGGER.error(msg, ex);
			throw new ConnectionException(msg, ex);
		}
	}

	public IPFSService configureTimeout(Integer timeout) {
		this.ipfsTimeout = timeout;
		return this;
	}

	public IPFSService configureThreadPool(Integer poolSize) {
		this.pool = Executors.newFixedThreadPool(poolSize);
		return this;
	}

	public IPFSService configureRetry(Integer maxRetry) {
		return this.configureRetry(maxRetry, Duration.ofSeconds(1));
	}

	public IPFSService configureRetry(Integer maxRetry, Duration delay) {
		this.retryPolicy = new RetryPolicy<>()
				.handle(IOException.class)
				.handle(ExecutionException.class)
				.handle(TimeoutException.class)
				.withDelay(delay)
				.withMaxRetries(maxRetry);

		return this;
	}

	public IPFSService addReplica(PinningService pinningService) {
		this.replicaSet.add(pinningService);
		return this;
	}

	public ImageDTO addFile(ImageDTO imageDTO) throws IOException {
		imageDTO.setCid(write(imageDTO.getMultipartFile().getBytes()));

		// add file to storage
		ipfsFileManager.addFileToStorage(imageDTO);

		// add file to db
		dbConsensusManager.storeImageObject(imageDTO);

		return imageDTO;
	}

	public void removeImageObject(ImageDTO imageDTO) {
		dbConsensusManager.removeImageObject(imageDTO);
		ipfsFileManager.removeFileFromStorage(imageDTO);
	}

	public IpfsStatusDTO checkingMissingImagesInIPFS() {
		List<String> pinnedImagesOnIPFS = getTracked();
		List<String> activeObjects = dbConsensusManager.loadImageObjectsByActiveStatus(true);

		AtomicBoolean status = new AtomicBoolean(true);
		activeObjects.parallelStream().forEach(cid -> {
			if (!pinnedImagesOnIPFS.contains(cid)) {
				status.set(false);
			}
		});

		return IpfsStatusDTO.getMissingImageStatus(activeObjects.size() + "/" + pinnedImagesOnIPFS.size(), status.get() ? "OK" : "NOT OK");
	}

	public IpfsStatusDTO uploadMissingImagesInIPFS() {
		List<String> pinnedImagesOnIPFS = getTracked();
		List<String> activeObjects = dbConsensusManager.loadImageObjectsByActiveStatus(true);

		LOGGER.debug("Start pinning missing images");
		activeObjects.forEach(cid -> {
			LOGGER.debug("Loadded CID: " + cid);
			if (!pinnedImagesOnIPFS.contains(cid)) {
				try {
					LOGGER.debug("Start reading/upload image from/to node for cid: " + cid);
					OutputStream os = this.read(cid);

					if (!os.toString().isEmpty()) {
						LOGGER.error("Start pin cid: " + cid);
						this.pin(cid);
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		});

		return checkingMissingImagesInIPFS();
	}

	public IpfsStatusDTO statusImagesInDB() {
		List<ImageDTO> notActiveImageObjects = dbConsensusManager.loadUnusedImageObject(timeStoringUnusedObjects);

		return IpfsStatusDTO.getMissingImageStatus(String.valueOf(notActiveImageObjects.size()), notActiveImageObjects.size() == 0 ? "OK" : "Images can be removed");
	}

	public IpfsStatusDTO removeUnusedImageObjectsFromSystemAndUnpinningThem() throws IOException {
		List<ImageDTO> notActiveImageObjects = dbConsensusManager.loadUnusedImageObject(timeStoringUnusedObjects);

		notActiveImageObjects.parallelStream().forEach(image -> {
			this.unpin(image.getCid());
			removeImageObject(image);
		});

		dbConsensusManager.removeUnusedImageObject(timeStoringUnusedObjects);

		clearIpfsRepo();

		return statusImagesInDB();
	}

	private void clearIpfsRepo() throws IOException {
		this.ipfs.repo.gc();
	}

	public IpfsStatusDTO getIpfsStatus() throws IOException, UnirestException {
		HttpResponse<String> response = Unirest.get(String.format(BASE_URI + "api/v0/id", ipfs.protocol, ipfs.host, ipfs.port)).asString();
		TreeMap objectTreeMap = new Gson().fromJson(response.getBody(), TreeMap.class);

		return new IpfsStatusDTO()
				.setStatus("CONNECTED")
				.setPeerId(ipfs.config.get("Identity.PeerID").toString())
				.setVersion(objectTreeMap.get("AgentVersion").toString())
				.setGateway(ipfs.config.get("Addresses.Gateway").toString())
				.setApi(ipfs.config.get("Addresses.API").toString())
				.setAddresses(objectTreeMap.get("Addresses").toString())
				.setPublicKey(objectTreeMap.get("PublicKey").toString());
	}

	@Override
	public Set<PinningService> getReplicaSet() {
		return replicaSet;
	}

	@Override
	public String write(InputStream content) {

		try {
			return this.write(IOUtils.toByteArray(content));

		} catch (IOException ex) {
			LOGGER.error("Exception converting Inputstream to byte array", ex);
			throw new TechnicalException("Exception converting Inputstream to byte array", ex);
		}
	}

	@Override
	public String write(byte[] content) {
		LOGGER.debug("Write file on IPFS");

		return Failsafe.with(retryPolicy)
				.onFailure(event -> LOGGER.error(String.format("Exception writting file on IPFS after %d attemps. %s", event.getAttemptCount(), event.getResult())))
				.onSuccess(event -> LOGGER.debug(String.format("File written on IPFS: hash=%s ", event.getResult())))
				.get(() -> {
					NamedStreamable.ByteArrayWrapper file = new NamedStreamable.ByteArrayWrapper(content);
					MerkleNode response = this.ipfs.add(file).get(0);
					return response.hash.toString();
				});
	}

	@Override
	public boolean pin(String cid) {
		LOGGER.debug(String.format("Pin CID %s on IPFS", cid));

		try {
			Failsafe.with(retryPolicy)
					.onFailure(event -> LOGGER.error(String.format("Exception pinning cid %s on IPFS after %d attemps", cid, event.getAttemptCount())))
					.onSuccess(event -> LOGGER.debug(String.format("CID %s pinned on IPFS", cid)))
					.run(() -> {
						Multihash hash = Multihash.fromBase58(cid);
//				replicaSet.forEach(replica -> {
//					replica.pin(cid);
//				});
						this.ipfs.pin.add(hash);
					});

			return true;
		} catch (Exception e) {
			return false;
		}
	}

	@Override
	public boolean unpin(String cid) {
		LOGGER.debug(String.format("Unpin CID %s on IPFS", cid));

		try {
			Failsafe.with(retryPolicy)
					.onFailure(event -> LOGGER.error(String.format("Exception unpinning cid %s on IPFS after %d attemps", cid, event.getAttemptCount())))
					.onSuccess(event -> LOGGER.debug(String.format("CID %s unpinned on IPFS", cid)))
					.run(() -> {
						Multihash hash = Multihash.fromBase58(cid);
//					replicaSet.forEach(replica -> {
//						replica.unpin(cid);
//					});
						this.ipfs.pin.rm(hash);
					});
			return true;
		} catch (Exception e) {
			return false;
		}

	}

	@Override
	public List<String> getTracked() {
		LOGGER.debug("Get pinned files on IPFS");

		return Failsafe.with(retryPolicy)
				.onFailure(event -> LOGGER.error(String.format("Exception getting pinned files on IPFS after %d attemps", event.getAttemptCount())))
				.onSuccess(event -> LOGGER.debug(String.format("Get pinned files on IPFS: %s", event.getResult())))
				.get(() -> {
					Map<Multihash, Object> cids = this.ipfs.pin.ls(IPFS.PinType.recursive);

					return cids.entrySet().stream()
							.map(e-> e.getKey().toBase58())
							.collect(Collectors.toList());
				});
	}

	@Override
	public OutputStream read(String cid) {
		try {
			return read(cid, new ByteArrayOutputStream());
		} catch (FailsafeException e) {
			ImageDTO imageDTO = dbConsensusManager.loadImageObjectIfExist(cid);
			if (imageDTO == null) {
				throw new TechnicalException(e.getMessage(), e);
			}

			try {
				Path filepath = ipfsFileManager.getFileByCid(imageDTO.getCid(), imageDTO.getExtension());
				String returnedCid = write(Files.readAllBytes(filepath));
				assert returnedCid.equals(cid);
			} catch (Exception e1) {
				throw new TechnicalException("Execution exception while adding new file to IPFS [id: " + cid + "]", e1);
			}

			return read(cid, new ByteArrayOutputStream());
		}
	}

	@Override
	public OutputStream read(String id, OutputStream output) {
		LOGGER.debug(String.format("Read file on IPFS [id: %s]", id));

		return Failsafe.with(retryPolicy)
				.onFailure(event -> LOGGER.error(String.format("Exception reading file [id: %s] on IPFS after %d attemps. %s", id, event.getAttemptCount(), event.getResult())))
				.onSuccess(event -> LOGGER.debug(String.format("File read on IPFS: [id: %s] ", id)))
				.get(() -> {
					try {
						Multihash filePointer = Multihash.fromBase58(id);

						Future<byte[]> ipfsFetcherResult = pool.submit(new IPFSContentFetcher(ipfs, filePointer));

						byte[] content = ipfsFetcherResult.get(ipfsTimeout, TimeUnit.MILLISECONDS);
						IOUtils.write(content, output);

						return output;

					} catch (java.util.concurrent.TimeoutException ex) {
						LOGGER.error(String.format("Timeout Exception while fetching file from IPFS [id: %s, timeout: %d ms]", id,
								ipfsTimeout));
						throw new TimeoutException("Timeout Exception while fetching file from IPFS [id: " + id + "]");

					} catch (InterruptedException ex) {
						LOGGER.error(String.format("Interrupted Exception while fetching file from IPFS [id: %s]", id));
						Thread.currentThread().interrupt();
						throw new TechnicalException("Interrupted Exception while fetching file from IPFS [id: " + id + "]", ex);

					} catch (ExecutionException ex) {
						LOGGER.error(String.format("Execution Exception while fetching file from IPFS [id: %s]", id), ex);
						throw new TechnicalException("Execution Exception while fetching file from IPFS [id: " + id + "]", ex);

					} catch (IOException ex) {
						LOGGER.error(String.format("IOException while fetching file from IPFS [id: %s]", id), ex);
						throw new TechnicalException("Execution Exception while fetching file from IPFS [id: " + id + "]", ex);
					}
				});

	}

	private class IPFSContentFetcher implements Callable<byte[]> {

		private final IPFS ipfs;
		private final Multihash multihash;

		public IPFSContentFetcher(IPFS ipfs, Multihash multihash) {
			this.ipfs = ipfs;
			this.multihash = multihash;
		}

		@Override
		public byte[] call() {
			try {
				return this.ipfs.cat(multihash);
			} catch (IOException ex) {
				LOGGER.error(String.format("Exception while fetching file from IPFS [hash: %s]", multihash), ex);
				throw new TechnicalException("Exception while fetching file from IPFS " + multihash, ex);
			}
		}
	}

}
