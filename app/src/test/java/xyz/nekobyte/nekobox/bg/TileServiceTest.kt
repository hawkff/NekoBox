package xyz.nekobyte.nekobox.bg

import android.service.quicksettings.Tile
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.nekobyte.nekobox.R
import xyz.nekobyte.nekobox.bg.BaseService.State

class TileServiceTest {
    @Test
    fun idleShowsAsOff_insteadOfThrowingInTheServiceProcess() {
        assertEquals(TileLook(R.drawable.ic_service_idle, Tile.STATE_INACTIVE, R.string.off), tileLook(State.Idle))
        assertEquals(tileLook(State.Stopped), tileLook(State.Idle))
    }

    @Test
    fun everyStateHasALook() {
        assertEquals(TileLook(R.drawable.ic_service_busy, Tile.STATE_ACTIVE, R.string.connecting), tileLook(State.Connecting))
        // Connected, the subtitle names the profile.
        assertEquals(TileLook(R.drawable.ic_service_active, Tile.STATE_ACTIVE, null), tileLook(State.Connected))
        assertEquals(TileLook(R.drawable.ic_service_busy, Tile.STATE_UNAVAILABLE, R.string.stopping), tileLook(State.Stopping))
    }
}
