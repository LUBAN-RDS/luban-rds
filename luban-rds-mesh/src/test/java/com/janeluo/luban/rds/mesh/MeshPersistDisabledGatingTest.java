package com.janeluo.luban.rds.mesh;

import com.janeluo.luban.rds.mesh.core.MeshState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q3（2026-09-11 mesh 审计 P2）：mesh-persist=no 时持久性门控短路——
 * durableGatingActive=false 语义下 durableIndex 视为已跟上（弱持久显式化），
 * 默认（persist 开启）门控照常。
 */
class MeshPersistDisabledGatingTest {

    @Test
    void defaultGatingActive() {
        MeshNode node = new MeshNode(
                MeshConfig.builder("a").build(), new MeshState(), null);
        assertTrue(node.isDurableGatingActive(), "默认应启用持久性门控");
    }

    @Test
    void persistDisabledShortCircuitsGating() {
        MeshNode node = new MeshNode(
                MeshConfig.builder("a").build(), new MeshState(), null);
        node.setDurableGatingActive(false);
        assertFalse(node.isDurableGatingActive(), "mesh-persist=no 应短路门控");
    }
}
