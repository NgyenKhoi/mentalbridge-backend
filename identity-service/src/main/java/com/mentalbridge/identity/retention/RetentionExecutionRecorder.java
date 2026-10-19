package com.mentalbridge.identity.retention;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class RetentionExecutionRecorder {

	private final RetentionExecutionRecordRepository executionRecords;

	public RetentionExecutionRecorder(RetentionExecutionRecordRepository executionRecords) {
		this.executionRecords = executionRecords;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RetentionExecutionRecordEntity recordStart(RetentionExecutionRecordEntity record) {
		return executionRecords.save(record);
	}

	/**
	 * Records a FAILED execution record in an isolated new transaction (REQUIRES_NEW).
	 * This guarantees that the failure record is committed and survives the outer business
	 * transaction rollback caused by exceptions thrown across the HTTP boundary.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RetentionExecutionRecordEntity recordFailure(RetentionExecutionRecordEntity record) {
		return executionRecords.save(record);
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public RetentionExecutionRecordEntity recordStale(RetentionExecutionRecordEntity record) {
		return executionRecords.save(record);
	}

	/**
	 * Records a SUCCEEDED execution record within the current business transaction (REQUIRED).
	 * If the surrounding transaction rolls back, this execution record rolls back with it,
	 * preventing false success execution evidence.
	 */
	@Transactional(propagation = Propagation.REQUIRED)
	public RetentionExecutionRecordEntity recordSuccess(RetentionExecutionRecordEntity record) {
		return executionRecords.save(record);
	}
}
