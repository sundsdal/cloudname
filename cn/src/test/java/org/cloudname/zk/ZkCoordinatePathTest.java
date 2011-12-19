package org.cloudname.zk;

import org.cloudname.Coordinate;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ZkCoordinatePathTest {
    @Test
    public void testSimple() throws Exception {
        Coordinate coordinate = new Coordinate(42 /*instance*/,  "service",  "user",  "cell", false /*validate*/);
        assertEquals("/cn/cell/user/service/42/config", ZkCoordinatePath.getConfigPath(coordinate, null));
        assertEquals("/cn/cell/user/service/42/config/name", ZkCoordinatePath.getConfigPath(coordinate, "name"));
        assertEquals("/cn/cell/user/service/42", ZkCoordinatePath.getRoot(coordinate));
        assertEquals("/cn/cell/user/service/42/status", ZkCoordinatePath.getStatusPath(coordinate));
        assertEquals("/cn/cell/user/service", ZkCoordinatePath.coordinateWithoutInstanceAsPath("cell", "user", "service"));
        assertEquals("/cn/cell/user/service/42", ZkCoordinatePath.coordinateAsPath("cell", "user", "service", 42));
    }
}
