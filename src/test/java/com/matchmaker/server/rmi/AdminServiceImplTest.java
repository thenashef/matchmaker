package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceImplTest {

    @Test
    void allMethods_throwUnsupportedOperationException() throws Exception {
        AdminServiceImpl adminService = new AdminServiceImpl(new SessionManager());
        GameTypeDTO dummyGameType = new GameTypeDTO(0, "Checkers", "desc", 2, 2, 8, 8);

        assertThrows(UnsupportedOperationException.class, () -> adminService.listGameTypes("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.addGameType("token", dummyGameType));
        assertThrows(UnsupportedOperationException.class, () -> adminService.listUsers("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.listActiveSessions("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.forceEndSession("token", 1));
    }
}
