// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.task;

import org.apache.doris.alter.AlterJobV2;
import org.apache.doris.thrift.TAlterTabletReqV2;
import org.apache.doris.thrift.TTaskType;

/*
 * This task is used for alter table process, such as rollup and schema change
 * The task will do data transformation from base replica to new replica.
 * The new replica should be created before.
 * The new replica can be a rollup replica, or a shadow replica of schema change.
 */
public class AlterReplicaTask extends AgentTask {

    private long baseTabletId;
    private long newReplicaId;
    private int baseSchemaHash;
    private int newSchemaHash;
    private long version;
    private long versionHash;
    private long jobId;
    private AlterJobV2.JobType jobType;

    public AlterReplicaTask(long backendId, long dbId, long tableId,
            long partitionId, long rollupIndexId, long baseIndexId, long rollupTabletId,
            long baseTabletId, long newReplicaId, int newSchemaHash, int baseSchemaHash,
            long version, long versionHash, long jobId, AlterJobV2.JobType jobType) {
        super(null, backendId, TTaskType.ALTER, dbId, tableId, partitionId, rollupIndexId, rollupTabletId);

        this.baseTabletId = baseTabletId;
        this.newReplicaId = newReplicaId;

        this.newSchemaHash = newSchemaHash;
        this.baseSchemaHash = baseSchemaHash;

        this.version = version;
        this.versionHash = versionHash;
        this.jobId = jobId;

        this.jobType = jobType;
    }

    public long getBaseTabletId() {
        return baseTabletId;
    }

    public long getNewReplicaId() {
        return newReplicaId;
    }

    public int getNewSchemaHash() {
        return newSchemaHash;
    }

    public int getBaseSchemaHash() {
        return baseSchemaHash;
    }

    public long getVersion() {
        return version;
    }

    public long getVersionHash() {
        return versionHash;
    }

    public long getJobId() {
        return jobId;
    }

    public AlterJobV2.JobType getJobType() {
        return jobType;
    }

    public TAlterTabletReqV2 toThrift() {
        TAlterTabletReqV2 req = new TAlterTabletReqV2(baseTabletId, signature, baseSchemaHash, newSchemaHash);
        req.setAlter_version(version);
        req.setAlter_version_hash(versionHash);
        return req;
    }
}
