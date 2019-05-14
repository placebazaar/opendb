package org.openplacereviews.opendb.ops;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.openplacereviews.opendb.FailedVerificationException;
import org.openplacereviews.opendb.util.JsonFormatter;

import java.util.*;

import static org.junit.Assert.*;
import static org.openplacereviews.opendb.ObjectGeneratorTest.generateOperations;
import static org.openplacereviews.opendb.VariableHelperTest.serverKeyPair;
import static org.openplacereviews.opendb.VariableHelperTest.serverName;

public class OpBlockchainTests {

	private OpBlockChain blc;

	@Rule
	public ExpectedException exceptionRule = ExpectedException.none();

	@Before
	public void beforeEachTestMethod() throws FailedVerificationException {
		JsonFormatter formatter = new JsonFormatter();
		blc = new OpBlockChain(OpBlockChain.NULL, new OpBlockchainRules(formatter, null));
		generateOperations(formatter, blc, serverKeyPair);
	}

	@Test
	public void testOpBlockChain() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());

		opBlockChain.replicateBlock(opBlock);
		blc.rebaseOperations(opBlockChain);

		assertTrue(opBlockChain.changeToEqualParent(opBlockChain.getParent()));

		OpBlockChain opBlockChain1 = new OpBlockChain(blc, opBlockChain, blc.getRules());
		assertNotNull(opBlockChain1);
	}

	@Test
	public void testOpBlockChainWithNotEqualParents() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());

		opBlockChain.replicateBlock(opBlock);

		exceptionRule.expect(IllegalStateException.class);
		new OpBlockChain(blc, opBlockChain, blc.getRules());
	}

	@Test
	public void testBlockChainStatus() {
		assertEquals(OpBlockChain.UNLOCKED, blc.getStatus());
	}

	@Test
	public void testValidationLocked() {
		blc.validateLocked();

		assertEquals(OpBlockChain.LOCKED_STATE, blc.getStatus());
	}

	@Test
	public void lockByUser() {
		blc.lockByUser();
		assertEquals(OpBlockChain.LOCKED_BY_USER, blc.getStatus());
	}

	@Test
	public void lockByUserIfBlockChainStatusIsLockedWithException() {
		blc.validateLocked();

		exceptionRule.expect(IllegalStateException.class);
		blc.lockByUser();
	}

	@Test
	public void unlockByUserIfBlockChainStatusLockedByUser() {
		blc.lockByUser();
		assertEquals(OpBlockChain.LOCKED_BY_USER, blc.getStatus());

		blc.unlockByUser();
		assertEquals(OpBlockChain.UNLOCKED, blc.getStatus());
	}

	@Test
	public void unlockByUserIfBlockChainStatusIsNotLockedByUserExpectException() {
		blc.validateLocked();
		assertEquals(OpBlockChain.LOCKED_STATE, blc.getStatus());

		exceptionRule.expect(IllegalStateException.class);
		blc.unlockByUser();
    }

    @Test
	public void testCreateBlock() throws FailedVerificationException {
		assertFalse(blc.getQueueOperations().isEmpty());
		assertEquals(-1, blc.getLastBlockId());

		assertNotNull(blc.createBlock(serverName, serverKeyPair));

		assertTrue(blc.getQueueOperations().isEmpty());
		assertEquals(0, blc.getLastBlockId());
	}

	@Test
	public void testCreateBlockWithLockedBlockChain() throws FailedVerificationException {
		assertFalse(blc.getQueueOperations().isEmpty());
		assertEquals(-1, blc.getLastBlockId());

		blc.validateLocked();
		assertEquals(OpBlockChain.LOCKED_STATE, blc.getStatus());

		exceptionRule.expect(IllegalStateException.class);
		blc.createBlock(serverName, serverKeyPair);
	}

	@Test
	public void testCreateBlockWithEmptyQueueWithException() throws FailedVerificationException {
		testRemoveAllQueueOperationsIfQueueNotEmpty();

		exceptionRule.expect(IllegalArgumentException.class);
		blc.createBlock(serverName, serverKeyPair);
	}

	@Test
	public void testRemoveAllQueueOperationsIfQueueNotEmpty() {
		assertFalse(blc.getQueueOperations().isEmpty());

		blc.removeAllQueueOperations();

		assertTrue(blc.getQueueOperations().isEmpty());
	}

	@Test
	public void testRemoveAllQueueOperationsIfQueueIsEmpty() {
		assertFalse(blc.getQueueOperations().isEmpty());

		assertTrue(blc.removeAllQueueOperations());

		assertTrue(blc.getQueueOperations().isEmpty());

		assertTrue(blc.removeAllQueueOperations());
	}

	@Test
	public void testRemoveQueueOperationsByListOfRowHashes() {
		final int amountOperationsForRemoving = 5;

		Deque<OpOperation> dequeOperations = blc.getQueueOperations();
		assertFalse(dequeOperations.isEmpty());

		int i = 0;
		Set<String> operationsToDelete = new HashSet<>();

		Iterator<OpOperation> iterator = dequeOperations.descendingIterator();
		while (i < amountOperationsForRemoving) {
			operationsToDelete.add(iterator.next().getRawHash());
			i++;
		}

		Set<String> removedOperations = blc.removeQueueOperations(operationsToDelete);
		assertEquals(amountOperationsForRemoving, removedOperations.size());
	}

	@Test
	public void testRemoveQueueOperationsByEmptyListOfHashes() {
		assertFalse(blc.getQueueOperations().isEmpty());

		Set<String> operationsToDelete = new HashSet<>();

		assertTrue(blc.removeQueueOperations(operationsToDelete).isEmpty());
	}

	@Test
	public void testRemoveQueueOperationsByNotExistingHash() {
		assertFalse(blc.getQueueOperations().isEmpty());

		Set<String> operationsToDelete = new HashSet<>();
		operationsToDelete.add(UUID.randomUUID().toString());

		assertTrue(blc.removeQueueOperations(operationsToDelete).isEmpty());
	}

	@Test
	public void testReplicateBlockWithNotImmutableOpBlock() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);
		opBlock.isImmutable = false;

		exceptionRule.expect(IllegalStateException.class);
		blc.replicateBlock(opBlock);
	}

	@Test
	public void testReplicateBlockWitLockedBlockChain() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		blc.validateLocked();
		assertEquals(OpBlockChain.LOCKED_STATE, blc.getStatus());

		exceptionRule.expect(IllegalStateException.class);
		blc.replicateBlock(opBlock);
	}

	@Test
	public void testReplicateBlockWithEmptyOperationQueue() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());

		assertNotNull(opBlockChain.replicateBlock(opBlock));

		assertEquals(0, opBlockChain.getLastBlockId());
	}

	@Test
	public void testReplicateBlockWithNotEmptyOperationQueue() throws FailedVerificationException {
		OpOperation opOperation = blc.getQueueOperations().removeFirst();

		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());
		opBlockChain.addOperation(opOperation);

		assertNull(opBlockChain.replicateBlock(opBlock));
	}

	@Test
	public void testRebaseOperations() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());

		assertNotNull(opBlockChain.replicateBlock(opBlock));
		assertTrue(blc.rebaseOperations(opBlockChain));

		assertEquals(blc.getParent(), opBlockChain);
	}

	@Test
	public void testRebaseOperationsWithNotEmptyOperationQueue() throws FailedVerificationException {
		OpOperation opOperation = blc.getQueueOperations().removeFirst();
		blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());
		opBlockChain.addOperation(opOperation);

		assertFalse(blc.rebaseOperations(opBlockChain));
	}

	@Test
	public void testChangeToEqualParent() throws FailedVerificationException {
		OpBlock opBlock = blc.createBlock(serverName, serverKeyPair);

		OpBlockChain opBlockChain = new OpBlockChain(blc.getParent(), blc.getRules());
		opBlockChain.replicateBlock(opBlock);

		blc.rebaseOperations(opBlockChain);

		assertTrue(opBlockChain.changeToEqualParent(opBlockChain.getParent()));
	}

	@Test
	public void testChangeToEqualLockedParent() {
		OpBlockChain newOp = new OpBlockChain(OpBlockChain.NULL, blc.getRules());
		newOp.lockByUser();

		exceptionRule.expect(IllegalStateException.class);
		blc.changeToEqualParent(newOp);
	}

	@Test
	public void testChangeToNotEqualParent() throws FailedVerificationException {
		blc.createBlock(serverName, serverKeyPair);
		OpBlockChain opBlockChain = new OpBlockChain(OpBlockChain.NULL, blc.getRules());

		assertFalse(opBlockChain.changeToEqualParent(blc));
	}

	@Test
	public void testAddOperations() {
		OpOperation loadedOperation = blc.getQueueOperations().getLast();
		OpOperation opOperation = new OpOperation(loadedOperation, true);
		opOperation.makeImmutable();

		int amountLoadedOperations = blc.getQueueOperations().size();
		blc.removeQueueOperations(new HashSet<>(Collections.singletonList(loadedOperation.getRawHash())));

		assertEquals(amountLoadedOperations - 1, blc.getQueueOperations().size());
		assertTrue(blc.addOperation(opOperation));
	}

	@Test
	public void testAddOperationsIfOperationIsAlreadyExists() {
		OpOperation loadedOperation = blc.getQueueOperations().getLast();
		OpOperation opOperation = new OpOperation(loadedOperation, true);
		opOperation.makeImmutable();

		exceptionRule.expect(IllegalArgumentException.class);
		blc.addOperation(opOperation);
	}

	@Test
	public void testAddOperationsWhenOperationIsMutable() {
		OpOperation opOperation = new OpOperation();

		exceptionRule.expect(IllegalStateException.class);
		blc.addOperation(opOperation);
	}

	@Test
	public void testAddOperationsWhenBlockChainIsLocked() {
		OpOperation opOperation = new OpOperation();
		opOperation.makeImmutable();

		blc.validateLocked(); // LOCKED state

		exceptionRule.expect(IllegalStateException.class);
		blc.addOperation(opOperation);
	}

	@Test
	public void  testValidateOperations() {
		OpOperation loadedOperation = blc.getQueueOperations().getLast();
		OpOperation opOperation = new OpOperation(loadedOperation, true);
		opOperation.makeImmutable();

		int amountLoadedOperations = blc.getQueueOperations().size();
		blc.removeQueueOperations(new HashSet<>(Collections.singletonList(loadedOperation.getRawHash())));
		assertEquals(amountLoadedOperations - 1, blc.getQueueOperations().size());

		assertTrue(blc.validateOperation(opOperation));
	}

	@Test
	public void  testValidateOperationsIfOperationIsAlreadyExists() {
		OpOperation loadedOperation = blc.getQueueOperations().getLast();
		OpOperation opOperation = new OpOperation(loadedOperation, true);
		opOperation.makeImmutable();

		exceptionRule.expect(IllegalArgumentException.class);
		blc.validateOperation(opOperation);
	}

	@Test
	public void testValidateOperationsWhenOperationIsMutable() {
		OpOperation opOperation = new OpOperation();

		exceptionRule.expect(IllegalStateException.class);
		blc.validateOperation(opOperation);
	}

	@Test
	public void testValidateOperationsWhenBlockChainIsLocked() {
		OpOperation opOperation = new OpOperation();
		opOperation.makeImmutable();

		blc.validateLocked(); // LOCKED state

		exceptionRule.expect(IllegalStateException.class);
		blc.validateOperation(opOperation);
	}

}
