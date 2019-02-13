package org.openplacereviews.opendb.service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.SecUtils;
import org.openplacereviews.opendb.Utils;
import org.openplacereviews.opendb.ops.OpBlock;
import org.openplacereviews.opendb.ops.OpDefinitionBean;
import org.openplacereviews.opendb.ops.OpenDBOperationExec;
import org.openplacereviews.opendb.ops.OperationsRegistry;
import org.openplacereviews.opendb.ops.auth.SignUpOperation;
import org.openplacereviews.opendb.service.LogOperationService.OperationStatus;
import org.openplacereviews.opendb.service.OpenDBUsersRegistry.ActiveUsersContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;


@Service
public class BlocksManager {

	@Autowired
	public OperationsQueueManager queue;
	
	@Autowired
	public OperationsRegistry registry;
	
	@Autowired
	public LogOperationService logSystem;
	
	@Autowired
	public OpenDBUsersRegistry usersRegistry;
	
	@Autowired
	public JdbcTemplate jdbcTemplate;

	public static final int MAX_BLOCK_SIZE = 1000;
	
	public static final int MAX_BLOCK_SIZE_MB = 1 << 20;
	
	public static final int BLOCK_VERSION = 1;
	
	
	private OpBlock prevOpBlock = new OpBlock();
	private boolean blockCreationPaused = true;
	private String serverUser;
	private String serverPrivateKey;
	private KeyPair serverKeyPair;
	private String blockCreationDetails = "";
	private long blockExtra = 0;
	
	public BlocksManager() {
		init();
	}

	public synchronized String createBlock() {
		OpBlock bl = new OpBlock();
		List<OpDefinitionBean> candidates = bl.getOperations();
		ConcurrentLinkedQueue<OpDefinitionBean> q = queue.getOperationsQueue();
		ActiveUsersContext users = pickupOpsFromQueue(candidates, q, false);
		return executeBlock(bl, users, false);
	}
	
	public synchronized String replicateBlock(OpBlock remoteBlock) {
		LinkedList<OpDefinitionBean> ops = new LinkedList<>(remoteBlock.getOperations());
		ArrayList<OpDefinitionBean> cand = new ArrayList<>();
		try {
			ActiveUsersContext users = pickupOpsFromQueue(cand, ops, true);
			if(ops.size() != 0) {
				throw new RuntimeException("The block could not validate all transactions included in it");
			}
			return executeBlock(remoteBlock, users, true);
		} catch (RuntimeException e) {
			throw e;
		}
	}
	
	public synchronized void init() {
		serverUser = System.getenv("OPENDB_SIGN_LOGIN");
		serverPrivateKey = System.getenv("OPENDB_SIGN_PK");
		// TODO load block hashes and forks
	}
	
	
	public synchronized boolean setBlockCreationPaused(boolean paused) {
		boolean p = blockCreationPaused;
		blockCreationPaused = paused;
		return p;
	}
	
	public boolean isBlockCreationPaused(boolean paused) {
		return blockCreationPaused;
	}
	

	
	
	
	private ActiveUsersContext pickupOpsFromQueue(List<OpDefinitionBean> candidates,
			Queue<OpDefinitionBean> q, boolean exceptionOnFail) {
		int size = 0;
		ActiveUsersContext au = new OpenDBUsersRegistry.ActiveUsersContext(usersRegistry.getBlockUsers());
		Map<String, Set<String>> authTxDependencies = new HashMap<String, Set<String>>();
		while(!q.isEmpty()) {
			OpDefinitionBean o = q.poll();
			int l = usersRegistry.toJson(o).length();
			String validMsg = null; 
			try {
				if(!usersRegistry.validateSignatures(au, o)) {
					validMsg = "not verified";
				}
				if(!usersRegistry.validateHash(o)) {
					validMsg = "hash is not valid";
				}
				if(!usersRegistry.validateSignatureHash(o)) {
					validMsg = "signature hash is not valid";
				}
			} catch (Exception e) {
				validMsg = e.getMessage();
			}
			if(validMsg != null) {
				logSystem.logOperation(OperationStatus.FAILED_PREPARE, o, String.format("Failed to verify operation signature: %s", validMsg),
						exceptionOnFail);
				continue;
			}
			
			if(l > MAX_BLOCK_SIZE_MB / 2) {
				logSystem.logOperation(OperationStatus.FAILED_PREPARE, o, String.format("Operation discarded due to size limit %d", l), exceptionOnFail);
				continue;
			}
			if(size + l > MAX_BLOCK_SIZE) {
				break;
			}
			if(candidates.size() >= MAX_BLOCK_SIZE) {
				break;
			}
			boolean authOp = au.addAuthOperation(o);
			if(authOp) {
				String uname = o.getStringValue(SignUpOperation.F_NAME);
				if(!authTxDependencies.containsKey(uname)) {
					authTxDependencies.put(uname, new LinkedHashSet<String>());
				}
				o.setTransientTxDependencies(new ArrayList<String>(authTxDependencies.get(uname)));
				authTxDependencies.get(uname).add(o.getHash());
			}
			candidates.add(o);
		}
		return au;
	}

	


	private String executeBlock(OpBlock block, ActiveUsersContext users, boolean exceptionOnFail) {
		List<OpenDBOperationExec> operations = prepareOperationCtxToExec(block, exceptionOnFail);
		if(block.blockId == 0) {
			signBlock(block, users);
		}
		validateBlock(block, users);
		
		// here we don't expect any failure or the will be fatal to the system
		executeOperations(block, operations);
		
		// don't keep operations in memory
		prevOpBlock = new OpBlock(block);
		return usersRegistry.toJson(block);
	}

	private void validateBlock(OpBlock block, ActiveUsersContext users) {
		if(!Utils.equals(usersRegistry.calculateMerkleTreeHash(block), block.merkleTreeHash)) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate merkle tree: %s %s", usersRegistry.calculateMerkleTreeHash(block), block.merkleTreeHash), true);
		}
		if(!Utils.equals(usersRegistry.calculateSigMerkleTreeHash(block), block.sigMerkleTreeHash)) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate signature merkle tree: %s %s", usersRegistry.calculateMerkleTreeHash(block), block.merkleTreeHash), true);
		}
		if(!Utils.equals(prevOpBlock.hash, block.previousBlockHash)) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate previous block hash: %s %s", prevOpBlock.hash, block.previousBlockHash), true);
		}
		if(!Utils.equals(calculateHash(block), block.hash)) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Failed to validate block hash: %s %s", calculateHash(block), block.hash), true);
		}
		if(prevOpBlock.blockId + 1 != block.blockId) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Block id doesn't match with previous block id: %d %d", prevOpBlock.blockId, block.blockId), true);
		}
		boolean validateSig = true;
		String eMsg = "";
		try {
			KeyPair pk = users.getLoginPublicKey(block.signedBy);
			byte[] blHash = SecUtils.getHashBytes(block.hash);		
			byte[] signature = SecUtils.decodeSignature(block.signature);
			if(pk == null || !SecUtils.validateSignature(pk, blHash, block.signatureAlgo, signature)) {
				validateSig = true;
			} else {
				validateSig = false;
			}
		} catch (FailedVerificationException e) {
			validateSig = false;
			eMsg = e.getMessage();
		} catch (RuntimeException e) {
			validateSig = false;
			eMsg = e.getMessage();
		}
		if (!validateSig) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block,
					String.format("Block id doesn't match with previous block id: %s", eMsg), true);
		}
	}

	private void signBlock(OpBlock block, ActiveUsersContext users) {
		try {
			block.date = System.currentTimeMillis();
			block.blockId = prevOpBlock.blockId + 1;
			block.previousBlockHash = prevOpBlock.hash;
			block.merkleTreeHash = usersRegistry.calculateMerkleTreeHash(block);
			block.sigMerkleTreeHash = usersRegistry.calculateSigMerkleTreeHash(block);
			block.signedBy = serverUser;
			block.version = BLOCK_VERSION;
			block.extra = blockExtra;
			block.details = blockCreationDetails;
			block.hash = calculateHash(block);
			byte[] hashBytes = SecUtils.getHashBytes(block.hash);
			if(serverKeyPair == null) {
				serverKeyPair = users.getLoginKeyPair(serverUser, serverPrivateKey);	
			}
			block.signature = SecUtils.signMessageWithKeyBase64(serverKeyPair, hashBytes, SecUtils.SIG_ALGO_NONE_EC, null);
			block.signatureAlgo = SecUtils.SIG_ALGO_NONE_EC;
		} catch (FailedVerificationException e) {
			logSystem.logBlock(OperationStatus.FAILED_VALIDATE, block, "Failed to sign the block: " + e.getMessage(), true);
		}
	}



	private String calculateHash(OpBlock block) {
		ByteArrayOutputStream bs = new ByteArrayOutputStream();
		DataOutputStream dous = new DataOutputStream(bs);
		try {
			dous.writeInt(block.version);
			dous.writeInt(block.blockId);
			dous.write(SecUtils.getHashBytes(block.previousBlockHash));
			dous.writeLong(block.date);
			dous.write(SecUtils.getHashBytes(block.merkleTreeHash));
			dous.write(SecUtils.getHashBytes(block.sigMerkleTreeHash));
			dous.writeLong(block.extra);
			if(!Utils.isEmpty(block.details)) {
				dous.write(block.details.getBytes("UTF-8"));
			}
			dous.write(block.signedBy.getBytes("UTF-8"));
			dous.flush();
			return SecUtils.calculateHashWithAlgo(SecUtils.HASH_SHA256, bs.toByteArray());
		} catch (IOException e) {
			throw new IllegalArgumentException(e);
		}
	}



	private void executeOperations(OpBlock block, List<OpenDBOperationExec> operations) {
		for (OpenDBOperationExec o : operations) {
			boolean execute = false;
			String err = "";
			try {
				execute = o.execute(jdbcTemplate);
			} catch (Exception e) {
				err = e.getMessage();
			}
			if (!execute) {
				logSystem.logOperation(OperationStatus.FAILED_EXECUTE,
						o.getDefinition(), String.format("Operations failed to execute: %s", err), true);
			} else {
				logSystem.logOperation(OperationStatus.EXECUTED, o.getDefinition(), "OK", false);
			}
		}
	}


	private List<OpenDBOperationExec> prepareOperationCtxToExec(OpBlock block, boolean exceptionOnFail) {
		List<OpenDBOperationExec> operations = new ArrayList<OpenDBOperationExec>();
		Map<String, OpDefinitionBean> executedTx = new TreeMap<String, OpDefinitionBean>();
		Iterator<OpDefinitionBean> it = block.getOperations().iterator();
		while(it.hasNext()) {
			OpDefinitionBean def = it.next();
			OpenDBOperationExec op = registry.createOperation(def);
			boolean valid = false;
			String err = "";
			try {
				valid = op != null && op.prepare(def);
			} catch (Exception e) {
				err = e.getMessage();
			}
			if(valid) { 
				boolean allDeps = checkAllDependencies(executedTx, def.getTransientTxDependencies());
				if(allDeps) {
					allDeps = checkAllDependencies(executedTx, def.getStringList(OpDefinitionBean.F_DEPENDENCIES));
				}
				if(!allDeps) {
					logSystem.logOperation(OperationStatus.FAILED_DEPENDENCIES, def, 
							String.format("Operations has dependencies there were not executed yet", def.getHash()), exceptionOnFail);
					valid = false;
				}
				if(executedTx.containsKey(def.getHash())) {
					logSystem.logOperation(OperationStatus.FAILED_EXECUTE, def, 
							String.format("Operations has duplicate hash in same block: %s", def.getHash()), exceptionOnFail);
					valid = false;
				}
			} else {
				logSystem.logOperation(OperationStatus.FAILED_PREPARE, def,
						String.format("Operations couldn't be validated for execution: %s", err));
			}
			if(valid) {
				operations.add(op);
				executedTx.put(def.getHash(), def);
			} else {
				// remove from block
				it.remove();
			}
		}
		return operations;
	}


	private boolean checkAllDependencies(Map<String, OpDefinitionBean> executedTx, List<String> dp) {
		for (String d : dp) {
			if (!executedTx.containsKey(d)) {
				return false;
			}
		}
		return true;
	}
	


	
}
