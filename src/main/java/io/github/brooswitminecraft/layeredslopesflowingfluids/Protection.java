package io.github.brooswitminecraft.layeredslopesflowingfluids;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * Best-effort respect for claim/protection mods. This mod's erosion is an environmental system,
 * not a player action, so there is no real player break to intercept; the established pattern
 * this org's own code already uses for a cancellable, non-player world change (see
 * {@code TownDonationBoxGameTests}' {@code BlockEvent.BreakEvent} construction in
 * dynamic-vehicles) is to post the same event a real break would, through a fake player, and
 * honor cancellation. This covers protection mods that listen on BreakEvent (the common case);
 * it is not a guarantee against every possible protection hook a mod might use instead.
 */
final class Protection {
    private Protection() {}

    static boolean mayErode(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return true;
        }
        BlockState state = serverLevel.getBlockState(pos);
        var fakePlayer = FakePlayerFactory.getMinecraft(serverLevel);
        BlockEvent.BreakEvent event = new BlockEvent.BreakEvent(serverLevel, pos, state, fakePlayer);
        NeoForge.EVENT_BUS.post(event);
        return !event.isCanceled();
    }
}
