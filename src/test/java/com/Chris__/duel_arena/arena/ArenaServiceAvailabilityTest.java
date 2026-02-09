package com.Chris__.duel_arena.arena;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public final class ArenaServiceAvailabilityTest {
    @TempDir
    Path tempDir;

    @Test
    void availabilitySnapshotCountsTotalValidReservedAndFree() {
        ArenaRepository repo = new ArenaRepository(tempDir, null);
        ArenaRepository.ArenaConfigFile cfg = new ArenaRepository.ArenaConfigFile();
        cfg.arenas.add(arena("alpha", "flat_world"));
        cfg.arenas.add(arena("beta", "flat_world"));
        cfg.arenas.add(arena("broken", ""));
        repo.save(cfg);
        repo.reload();

        ArenaService service = new ArenaService(repo, null);
        assertNotNull(service.allocateAnyFreeArena("S1"));

        ArenaService.ArenaAvailability snapshot = service.getAvailabilitySnapshot();
        assertEquals(3, snapshot.total());
        assertEquals(2, snapshot.valid());
        assertEquals(1, snapshot.reserved());
        assertEquals(1, snapshot.free());
    }

    private static Arena arena(String id, String world) {
        Arena arena = new Arena();
        arena.id = id;
        arena.world = world;
        arena.normalize();
        return arena;
    }
}
