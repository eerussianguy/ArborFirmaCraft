package com.therighthon.afc.common.blockentities;

import java.util.Map;
import java.util.Optional;
import com.therighthon.afc.AFC;
import com.therighthon.afc.common.AFCTags;
import com.therighthon.afc.common.blocks.TapBlock;
import com.therighthon.afc.common.fluids.AFCFluids;
import com.therighthon.afc.common.fluids.SimpleAFCFluid;
import com.therighthon.afc.common.recipe.AFCRecipeTypes;
import com.therighthon.afc.common.recipe.TapInventory;
import com.therighthon.afc.common.recipe.TreeTapRecipe;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import org.antlr.runtime.tree.Tree;
import org.apache.commons.io.input.TaggedInputStream;
import org.jetbrains.annotations.Nullable;

import net.dries007.tfc.client.TFCSounds;
import net.dries007.tfc.client.particle.FluidParticleOption;
import net.dries007.tfc.client.particle.TFCParticles;
import net.dries007.tfc.common.TFCTags;
import net.dries007.tfc.common.blocks.TFCBlocks;
import net.dries007.tfc.common.blocks.wood.LogBlock;
import net.dries007.tfc.common.blocks.wood.Wood;
import net.dries007.tfc.common.capabilities.Capabilities;
import net.dries007.tfc.common.fluids.Alcohol;
import net.dries007.tfc.common.fluids.FluidHelpers;
import net.dries007.tfc.common.fluids.MixingFluid;
import net.dries007.tfc.common.fluids.TFCFluids;
import net.dries007.tfc.common.recipes.ingredients.BlockIngredient;
import net.dries007.tfc.util.Helpers;
import net.dries007.tfc.util.calendar.Calendar;
import net.dries007.tfc.util.calendar.Calendars;
import net.dries007.tfc.util.calendar.Month;
import net.dries007.tfc.util.climate.Climate;
import net.dries007.tfc.world.chunkdata.ChunkData;

public class TapBlockEntity extends BlockEntity
{
    @Nullable private BlockPos pourPos = null;



    public TapBlockEntity(BlockPos pPos, BlockState pBlockState)
    {
        super(AFCBlockEntities.TAP_BLOCK_ENTITY.get(), pPos, pBlockState);
    }

    public static void tick(Level level, BlockPos pos, BlockState state, TapBlockEntity tap) {
        final Direction facing = state.getValue(TapBlock.FACING);
        tap.tickPouring(level, pos, facing);
    }


    //IDEA: What if I reuse the pouring barrel code?

    public static boolean canPour(IFluidHandler to, FluidStack fluidStack) {
        return to.fill(fluidStack, IFluidHandler.FluidAction.SIMULATE) == 1;
    }

    public static boolean pour(IFluidHandler to, FluidStack fluidStack) {
        if (canPour(to, fluidStack)) {
            to.fill(fluidStack, IFluidHandler.FluidAction.EXECUTE);
            return true;
        } else {
            return false;
        }
    }

    public static boolean isSpring(Level level) {
        Month currentMonth = Calendars.SERVER.getCalendarMonthOfYear();
        return (currentMonth==Month.FEBRUARY || currentMonth==Month.MARCH || currentMonth==Month.APRIL || currentMonth==Month.MAY || currentMonth==Month.JUNE);
    }

    public static boolean isTempOkay(Level level, BlockPos pos, float minTemp, float maxTemp)
    {
        final float currentTemp = Climate.getTemperature(level, pos);
        return (currentTemp > minTemp && currentTemp < maxTemp);
    }


    public void tickPouring(Level level, BlockPos pos, Direction facing)
    {
        //First bit checks if there is a valid container to pour into before starting the pour
        //Every 20 ticks...
        if (level.getGameTime() % 20 == 0)
        {
            final BlockPos pourPos = pos.below();
            final BlockEntity blockEntity = level.getBlockEntity(pourPos);

            //Get the position of the log block
            BlockPos logPos;
            switch(facing)
            {
                case NORTH: logPos = pos.south();
                    break;
                case SOUTH: logPos = pos.north();
                    break;
                case EAST: logPos = pos.west();
                    break;
                case WEST: logPos = pos.east();
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + facing);
            }

            //Check for a valid recipe for said log block before worrying about blockentities and nonsense
            BlockState logState = level.getBlockState(logPos);
            final TreeTapRecipe recipe = TreeTapRecipe.getRecipe(logState);

            if (recipe != null)
            {
                if (blockEntity != null)
                {
                    blockEntity.getCapability(Capabilities.FLUID, Direction.UP).ifPresent(cap -> {
                        //TODO: This is where we need to reference the recipe and get the fluid type to pass to canPour
                        if (canPour(cap, recipe.getOutput()))
                        {
                            this.pourPos = pourPos;
                        }
                        else
                        {
                            //Makes sure it won't visually pour into a sealed barrel
                            this.pourPos = null;
                        }
                    });
                }

                //Print logic to check it all out:
                if (recipe.requiresNaturalLog())
                {
                    AFC.LOGGER.debug("Recipe requires natural log. Recipe, valid log");
                    AFC.LOGGER.debug(recipe.toString());
                    AFC.LOGGER.debug(logState.getValue(LogBlock.NATURAL).toString());
                }
                if (isTempOkay(level, pos, recipe.getMinTemp(), recipe.getMaxTemp()))
                {
                    AFC.LOGGER.debug("Recipe is at appropriate temp. Min, Max:");
                    AFC.LOGGER.debug(String.valueOf(recipe.getMinTemp()));
                    AFC.LOGGER.debug(String.valueOf(recipe.getMaxTemp()));
                }
                if (recipe.springOnly())
                {
                    AFC.LOGGER.debug("Recipe is spring only. isSpring?:");
                    AFC.LOGGER.debug(String.valueOf(isSpring(level)));
                }

                //Check that the block the tap is on is natural, if required by the recipe. The idea is to support blocks other than TFC logs
                //Also checks if the recipe requires it be spring, and if so, if it is spring
                //Ternary is used (sloppily) to ensure that we don't ask for a "natural" logblock from a block that can't have it
                //It's sloppy, because if someone doesn't write the recipe correctly, then it will crash the game, but it should be a helpful crash, so...
                if (this.pourPos != null
                    && (recipe.requiresNaturalLog() ? logState.getValue(LogBlock.NATURAL) : true)
                    && isTempOkay(level, pos, recipe.getMinTemp(), recipe.getMaxTemp())
                    && (!recipe.springOnly() || isSpring(level)))
                {
                    //Needs to check if the block entity is removed every tick while pouring to avoid a crash
                    if (blockEntity != null)
                    {
                        final FluidStack fluidStack = recipe.getOutput();

                        if (blockEntity.getCapability(Capabilities.FLUID, Direction.UP).map(cap ->
                            pour(cap, fluidStack)).orElse(false))
                        {
                            if (level.getGameTime() % 20 == 0 && level instanceof ServerLevel server)
                            {
                                final double offset = -0.2;
                                final double dx = facing.getStepX() > 0 ? offset : facing.getStepX() < 0 ? -offset : 0;
                                final double dz = facing.getStepZ() > 0 ? offset : facing.getStepZ() < 0 ? -offset : 0;
                                final double x = pos.getX() + 0.5f + dx;
                                final double y = pos.getY() + 0.125f;
                                final double z = pos.getZ() + 0.5f + dz;

                                Helpers.playSound(level, pos, TFCSounds.BARREL_DRIP.get());
                                server.sendParticles(new FluidParticleOption(TFCParticles.BARREL_DRIP.get(), fluidStack.getFluid()), x, y, z, 1, 0, 0, 0, 1f);
                            }
                        }
                        else
                        {
                            this.pourPos = null;
                        }
                    }
                    else
                    {
                        this.pourPos = null;
                    }
                }
            }
        }
    }
}
