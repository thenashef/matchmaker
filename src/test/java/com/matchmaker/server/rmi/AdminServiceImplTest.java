package com.matchmaker.server.rmi;

import com.matchmaker.common.dto.GameTypeDTO;
import com.matchmaker.server.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.rmi.server.UnicastRemoteObject;

import static org.junit.jupiter.api.Assertions.assertThrows;

class AdminServiceImplTest {

    private AdminServiceImpl adminService;

    @BeforeEach
    void createAdminService() throws Exception {
        adminService = new AdminServiceImpl(new SessionManager());
    }

    @AfterEach
    void unexportAdminService() {
        if (adminService != null) {
            try { UnicastRemoteObject.unexportObject(adminService, true); } catch (Exception ignored) { }
        }
    }

    @Test
    void allMethods_throwUnsupportedOperationException() throws Exception {
        GameTypeDTO dummyGameType = new GameTypeDTO(0, "Checkers", "desc", 2, 2, 8, 8);

        assertThrows(UnsupportedOperationException.class, () -> adminService.listGameTypes("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.addGameType("token", dummyGameType));
        assertThrows(UnsupportedOperationException.class, () -> adminService.listUsers("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.listActiveSessions("token"));
        assertThrows(UnsupportedOperationException.class, () -> adminService.forceEndSession("token", 1));
    }
}
