package io.github.brooswitminecraft.layeredslopesflowingfluids.mixin;

import io.github.brooswitminecraft.layeredslopesflowingfluids.FlowingFluidsCompat;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * MINECRAFT-513 self-review 53094: Flowing Fluids' own {@code isManeuveringFluids} flag is not
 * set during its periodic random-leveling smoothing tick, only during its main scheduled tick
 * and its displacement path -- confirmed by decompiling the pinned jar. This mixin brackets the
 * one low-level setter every one of those paths ultimately calls
 * ({@code FFFluidUtils.setFluidStateAtPosToNewAmount}) with {@link FlowingFluidsCompat#levelingDepth},
 * covering every path Flowing Fluids itself moves fluid through at its single lowest common
 * point, including the one its own flag misses.
 *
 * <p>In its own dedicated sub-package: Mixin's {@code "package"} declaration in {@code
 * layeredslopesflowingfluids.mixins.json} claims ownership of every class in that package, so
 * this cannot live alongside this mod's ordinary classes without Mixin refusing to let them load
 * normally -- confirmed locally (a startup crash: "is in a defined mixin package ... and cannot
 * be referenced directly") before moving it here.
 *
 * <p>{@code @Pseudo} (targeting by class reference, since this mod already has a compileOnly
 * dependency on the pinned jar for {@link FlowingFluidsCompat}) is exactly the pattern Flowing
 * Fluids itself uses for its own optional Create-compatibility mixins (confirmed by decompiling
 * {@code MixinHosePulley} et al.): Mixin silently skips applying this mixin at all when the
 * target class cannot be found, which is the case for the entire standalone (no
 * {@code -PcoinstallFlowingFluids=true}) build and runtime -- no crash, no dependency, nothing
 * on the runtime classpath, matching every other door into Flowing Fluids this project uses.
 */
@Pseudo
@Mixin(value = traben.flowing_fluids.FFFluidUtils.class, remap = false)
abstract class LevelingMixin {
    @Inject(method = "setFluidStateAtPosToNewAmount", at = @At("HEAD"))
    private static void ls$before(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount,
            CallbackInfoReturnable<Boolean> cir) {
        FlowingFluidsCompat.levelingDepth++;
    }

    @Inject(method = "setFluidStateAtPosToNewAmount", at = @At("RETURN"))
    private static void ls$after(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount,
            CallbackInfoReturnable<Boolean> cir) {
        FlowingFluidsCompat.levelingDepth--;
    }
}
