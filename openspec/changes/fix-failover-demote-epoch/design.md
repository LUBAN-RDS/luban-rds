# Design: fix-failover-demote-epoch

## Fix 1: Bump old master configEpoch in onFailoverResult

**File**: `luban-rds-cluster/src/main/java/.../FailoverManager.java`

**Change**: In `onFailoverResult()`, after setting masterNodeId for the demoted old master, add:
```java
node.setConfigEpoch(msg.getNewConfigEpoch());
```

**Rationale**: The winner's epoch represents the new configuration version. The demoted node's configEpoch must be bumped to this value so that subsequent gossip entries carry an epoch strictly greater than the old master's locally-restored epoch, satisfying the `gossipEpoch > localEpochBaseline` gate in `handleMyselfGossipEntry`.

## Fix 2: Correct My Config Epoch header in nodes.conf

**File**: `luban-rds-cluster/src/main/java/.../ClusterConfigPersister.java`

**Change**: In `save()`, replace:
```java
writer.write("# My Config Epoch: " + config.getConfigEpoch());
```
with:
```java
ClusterNode myNode = config.getMyNode();
long myConfigEpoch = myNode != null ? myNode.getConfigEpoch() : config.getConfigEpoch();
writer.write("# My Config Epoch: " + myConfigEpoch);
```

**Rationale**: The `# My Config Epoch` header should reflect the MYSELF node's actual configEpoch stored in its node entry. Currently it tracks a separate `ClusterConfig.configEpoch` AtomicLong that is never meaningfully updated, causing persistent 0 and misleading diagnostics.

## Test Strategy

- Existing `GossipSelfDemoteTest` and `ClusterRestartDemoteTest` cover the gossip self-demotion path
- The fix is a one-line data propagation change — the epoch bump ensures existing logic works as designed
- Run existing tests to verify no regression
