package io.github.brooswitminecraft.layeredslopesflowingfluids.mixin;

import io.github.brooswitminecraft.layeredslopesflowingfluids.FlowingFluidsCompat;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

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
 *
 * <p>Self-review 53103: a plain {@code @Inject} HEAD/RETURN pair is not genuinely exception-safe
 * -- {@code RETURN} never fires on a thrown exception, so a same-tick manual edit made right
 * after could still misread as verified before the next tick's safety net runs. MixinExtras'
 * {@code @WrapMethod} is the one way to actually wrap the ORIGINAL target method call in a real
 * {@code try/finally}, closing that window outright rather than merely bounding it. Flowing
 * Fluids itself already depends on MixinExtras (confirmed by decompiling its own Create-compat
 * mixins), but this mod declares and jar-in-jar embeds its own
 * ({@code io.github.llamalad7:mixinextras-neoforge}) rather than relying on Flowing Fluids having
 * already bootstrapped it first -- the exact same jar-in-jar pattern used for every other small
 * bundled dependency in this ecosystem, and the artifact self-registers via FML as its own
 * {@code GAMELIBRARY} mod with its own bundled mixin config, needing no bootstrap call from this
 * mod's own code.
 */
@Pseudo
@Mixin(value = traben.flowing_fluids.FFFluidUtils.class, remap = false)
abstract class LevelingMixin {
    @WrapMethod(method = "setFluidStateAtPosToNewAmount")
    private static boolean ls$wrap(LevelAccessor levelAccessor, BlockPos pos, Fluid fluid, int newAmount,
            Operation<Boolean> original) {
        FlowingFluidsCompat.levelingDepth++;
        try {
            return original.call(levelAccessor, pos, fluid, newAmount);
        } finally {
            FlowingFluidsCompat.levelingDepth--;
        }
    }
}
