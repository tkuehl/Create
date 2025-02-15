package com.simibubi.create.content.logistics.depot;

import com.simibubi.create.AllRecipeTypes;
import com.simibubi.create.content.equipment.goggles.IHaveGoggleInformation;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.content.processing.basin.BasinBlock;
import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;

import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.inventory.InvManipulationBehaviour;
import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import com.simibubi.create.foundation.fluid.FluidHelper;
import com.simibubi.create.foundation.fluid.FluidIngredient;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import com.simibubi.create.foundation.item.SmartInventory;
import com.simibubi.create.foundation.recipe.RecipeApplier;
import com.simibubi.create.foundation.utility.Components;
import com.simibubi.create.foundation.utility.Couple;
import com.simibubi.create.foundation.utility.Lang;
import com.simibubi.create.foundation.utility.LangBuilder;
import com.simibubi.create.foundation.utility.VecHelper;
import com.simibubi.create.foundation.utility.animation.LerpedFloat;
import com.simibubi.create.infrastructure.config.AllConfigs;

import io.github.fabricators_of_create.porting_lib.fluids.FluidStack;
import io.github.fabricators_of_create.porting_lib.transfer.TransferUtil;
import io.github.fabricators_of_create.porting_lib.transfer.callbacks.TransactionCallback;
import io.github.fabricators_of_create.porting_lib.transfer.item.ItemStackHandler;
import io.github.fabricators_of_create.porting_lib.transfer.item.RecipeWrapper;
import io.github.fabricators_of_create.porting_lib.util.FluidTextUtil;
import io.github.fabricators_of_create.porting_lib.util.FluidUnit;
import io.github.fabricators_of_create.porting_lib.util.StorageProvider;
import it.unimi.dsi.fastutil.Pair;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.CombinedStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.commands.data.StorageDataAccessor;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class AdvancedDepotBlockEntity extends DepotBlockEntity implements IHaveGoggleInformation {

	DepotBehaviour depotBehaviour;

	private boolean needsUpdate; // fabric: need to delay to avoid doing stuff mid-transaction, causing a crash
	private boolean areFluidsMoving;
	LerpedFloat ingredientRotationSpeed;
	LerpedFloat ingredientRotation;

	public AdvancedDepotInventory inputInventory;
	public SmartFluidTankBehaviour inputTank;
	protected SmartFluidTankBehaviour outputTank;
	private boolean contentsChanged;

	private Couple<SmartFluidTankBehaviour> tanks;

	protected Storage<FluidVariant> fluidCapability;
	protected List<FluidStack> spoutputFluidBuffer;

	private final Map<Direction, StorageProvider<FluidVariant>> spoutputOutputs = new HashMap<>();

	private static final RecipeWrapper RECIPE_WRAPPER = new RecipeWrapper(new ItemStackHandler(1));

	public int runningTicks;
	public int processingTicks;

	SnapshotParticipant<Data> snapshotParticipant = new SnapshotParticipant<>() {
		@Override
		protected Data createSnapshot() {
			return new Data(spoutputFluidBuffer);
		}

		@Override
		protected void readSnapshot(Data snapshot) {
			spoutputFluidBuffer = snapshot.spoutputFluidBuffer;
		}
	};

	record Data(List<FluidStack> spoutputFluidBuffer) {
	}

	public AdvancedDepotBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);

		inputInventory = new AdvancedDepotInventory(9, this);
		inputInventory.whenContentsChanged(() -> contentsChanged = true);
		areFluidsMoving = false;
		contentsChanged = true;
		ingredientRotation = LerpedFloat.angular()
				.startWithValue(0);
		ingredientRotationSpeed = LerpedFloat.linear()
				.startWithValue(0);

		tanks = Couple.create(inputTank, outputTank);
	}

	@Override
	public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
		super.addBehaviours(behaviours);

		behaviours.add(new DirectBeltInputBehaviour(this));
		inputTank = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.INPUT, this, 2, FluidConstants.BUCKET, true)
				.whenFluidUpdates(() -> contentsChanged = true);
		outputTank = new SmartFluidTankBehaviour(SmartFluidTankBehaviour.OUTPUT, this, 2, FluidConstants.BUCKET, true)
				.whenFluidUpdates(() -> contentsChanged = true)
				.forbidInsertion();
		behaviours.add(inputTank);
		behaviours.add(outputTank);

		fluidCapability = new CombinedTankWrapper(inputTank.getCapability(), outputTank.getCapability());

		behaviours.add(depotBehaviour = new DepotBehaviour(this));
		depotBehaviour.addSubBehaviours(behaviours);
	}

	@Nullable
	@Override
	public Storage<ItemVariant> getItemStorage(@Nullable Direction direction) {
		return depotBehaviour.itemHandler;
	}

	public ItemStack getHeldItem() {
		return depotBehaviour.getHeldItemStack();
	}

	@Nullable
	@Override
	public Storage<FluidVariant> getFluidStorage(@Nullable Direction face) {
		return fluidCapability;
	}

	public boolean acceptOutputs(List<ItemStack> outputItems, List<FluidStack> outputFluids, TransactionContext ctx) {
		outputTank.allowInsertion();
		boolean acceptOutputsInner = acceptOutputsInner(outputItems, outputFluids, ctx);
		outputTank.forbidInsertion();
		return acceptOutputsInner;
	}

	private boolean acceptOutputsInner(List<ItemStack> outputItems, List<FluidStack> outputFluids, TransactionContext ctx) {
		BlockState blockState = getBlockState();
		if (!(blockState.getBlock() instanceof BasinBlock))
			return false;

		Direction direction = blockState.getValue(BasinBlock.FACING);
		snapshotParticipant.updateSnapshots(ctx);
		if (direction != Direction.DOWN) {

			BlockEntity be = level.getBlockEntity(worldPosition.below()
					.relative(direction));

			InvManipulationBehaviour inserter =
					be == null ? null : BlockEntityBehaviour.get(level, be.getBlockPos(), InvManipulationBehaviour.TYPE);
			Storage<FluidVariant> targetTank = getFluidSpoutputOutput(direction);
			boolean externalTankNotPresent = targetTank == null;

			if (!outputFluids.isEmpty() && externalTankNotPresent) {
				// Special case - fluid outputs but output only accepts items
				targetTank = outputTank.getCapability();
				if (targetTank == null)
					return false;
				if (!acceptFluidOutputsIntoBasin(outputFluids, ctx, targetTank))
					return false;
			}

			if (!externalTankNotPresent)
				for (FluidStack fluidStack : outputFluids)
					spoutputFluidBuffer.add(fluidStack.copy());
			return true;
		}

		Storage<FluidVariant> targetTank = outputTank.getCapability();

		if (outputFluids.isEmpty())
			return true;
		if (targetTank == null)
			return false;
		if (!acceptFluidOutputsIntoBasin(outputFluids, ctx, targetTank))
			return false;

		return true;
	}

	private boolean acceptFluidOutputsIntoBasin(List<FluidStack> outputFluids, TransactionContext ctx,
												Storage<FluidVariant> targetTank) {
		for (FluidStack fluidStack : outputFluids) {
			long fill = targetTank instanceof SmartFluidTankBehaviour.InternalFluidHandler
					? ((SmartFluidTankBehaviour.InternalFluidHandler) targetTank).forceFill(fluidStack.copy(), ctx)
					: targetTank.insert(fluidStack.getType(), fluidStack.getAmount(), ctx);
			if (fill != fluidStack.getAmount())
				return false;
		}
		return true;
	}

	@Override
	public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
		Lang.translate("gui.goggles.advanced_depot_fluid")
				.forGoggles(tooltip);

		boolean isEmpty = true;

		FluidUnit unit = AllConfigs.client().fluidUnitType.get();
		LangBuilder unitSuffix = Lang.translate(unit.getTranslationKey());
		boolean simplify = AllConfigs.client().simplifyFluidUnit.get();
		for (SmartFluidTankBehaviour behaviour : tanks) {
			for (SmartFluidTankBehaviour.TankSegment tank : behaviour.getTanks()) {
				FluidStack fluidStack = tank.getTank().getFluid();
				if (fluidStack.isEmpty())
					continue;
				Lang.text("")
						.add(Lang.fluidName(fluidStack)
								.add(Lang.text(" "))
								.style(ChatFormatting.GRAY)
								.add(Lang.text(FluidTextUtil.getUnicodeMillibuckets(fluidStack.getAmount(), unit, simplify))
										.add(unitSuffix)
										.style(ChatFormatting.BLUE)))
						.forGoggles(tooltip, 1);
				isEmpty = false;
			}
		}


		if (isEmpty)
			tooltip.remove(0);

		/// Display held item
		Lang.translate("gui.goggles.advanced_depot_item")
				.forGoggles(tooltip);

		ItemStack heldItem = getHeldItem();
		Lang.text("")
				.add(Lang.itemName(heldItem)
						.add(Lang.text(" "))
						.style(ChatFormatting.GRAY)
						.add(Lang.text(heldItem.getDisplayName().getString())
								.add(unitSuffix)
								.style(ChatFormatting.BLUE)))
				.forGoggles(tooltip, 1);

		/// Display blast recipe
		Lang.translate("gui.goggles.advanced_depot_recipe")
				.forGoggles(tooltip);

		Lang.text("")
				.add(Lang.itemName(heldItem)
						.add(Lang.text(" "))
						.style(ChatFormatting.GRAY)
						.add(Lang.text(String.valueOf(heldItem.getCount()))
								.add(unitSuffix)
								.style(ChatFormatting.BLUE)))
				.forGoggles(tooltip, 1);

		return true;
	}

	public Storage<FluidVariant> getFluidSpoutputOutput(Direction facing) {
		StorageProvider<FluidVariant> providers = spoutputOutputs.get(facing);
		return providers == null ? null : providers.get(facing.getOpposite());
	}

	class BasinValueBox extends ValueBoxTransform.Sided {

		@Override
		protected Vec3 getSouthLocation() {
			return VecHelper.voxelSpace(8, 12, 16.05);
		}

		@Override
		protected boolean isSideActive(BlockState state, Direction direction) {
			return direction.getAxis()
					.isHorizontal();
		}

	}

	public void notifyChangeOfContents() {
		contentsChanged = true;
	}

	@Override
	public void tick() {
		super.tick();
		long lavaAmount = 0;
		boolean hasLava = false;

		if (runningTicks >= 40) {
			runningTicks = 0;
			return;
		}

		for (SmartFluidTankBehaviour behaviour : tanks) {
			for (SmartFluidTankBehaviour.TankSegment tank : behaviour.getTanks()) {
				FluidStack fluidStack = tank.getTank().getFluid();
				if (fluidStack.getAmount() >= FluidConstants.DROPLET && FluidHelper.isLava(fluidStack.getFluid())) {
					lavaAmount = fluidStack.getAmount();
					hasLava = true;
				}
			}
		}

		ItemStack itemInStorage = getHeldItem();
		boolean canProcessItem = canProcess(itemInStorage, level);
		long consumedFluid = Math.round((FluidConstants.BUCKET / 64) * itemInStorage.getCount());
		if (hasLava && canProcessItem && lavaAmount >= consumedFluid) {
			if ((!level.isClientSide || isVirtual()) && runningTicks == 20) {
		if (processingTicks < 0) {
					int recipeSpeed = itemInStorage.getCount();
					//int speed = 512;
					processingTicks = recipeSpeed;
				} else {
					processingTicks--;
					if (processingTicks == 0) {
						runningTicks++;
						processingTicks = -1;

						if (lavaAmount > consumedFluid) {
							List<ItemStack> output = process(itemInStorage, level);
							if (output != null && !output.isEmpty()) {
								/// Set depot item to processed item
								TransportedItemStack transported = new TransportedItemStack(output.get(0));
								depotBehaviour.setHeldItem(transported);
								DecreaseFluidLevel(consumedFluid);
							}
						}
						sendData();
					}
				}
			}
		}

		if (runningTicks != 20)
			runningTicks++;
	}

	public boolean canProcess(ItemStack stack, Level level) {
		ItemStack blastingItem = GetBlastRecipeItem(stack);
		if (blastingItem != null)
			return true;

		return !stack.getItem()
				.isFireResistant();
	}

	private boolean DecreaseFluidLevel(long extractedAmount) {
		boolean fluidsAffected = false;
		try (Transaction t = TransferUtil.getTransaction()) {
			Storage<FluidVariant> availableFluids = getFluidStorage(null);
			for (StorageView<FluidVariant> view : availableFluids.nonEmptyViews()) {
				FluidStack fluidStack = new FluidStack(view);
				long drainedAmount = Math.min(extractedAmount, fluidStack.getAmount());
				if (view.extract(fluidStack.getType(), drainedAmount, t) == drainedAmount) {
					fluidsAffected = true;
				}
			}

			if (fluidsAffected) {
				TransactionCallback.onSuccess(t, () -> {
					this.getBehaviour(SmartFluidTankBehaviour.INPUT)
							.forEach(SmartFluidTankBehaviour.TankSegment::onFluidStackChanged);
					this.getBehaviour(SmartFluidTankBehaviour.OUTPUT)
							.forEach(SmartFluidTankBehaviour.TankSegment::onFluidStackChanged);
				});
			}
			t.commit();
		}
		return fluidsAffected;
	}

	@Nullable
	public ItemStack GetBlastRecipeItem(ItemStack stack) {
		RECIPE_WRAPPER.setItem(0, stack);
		Optional<SmeltingRecipe> smeltingRecipe = level.getRecipeManager()
				.getRecipeFor(RecipeType.SMELTING, RECIPE_WRAPPER, level)
				.filter(AllRecipeTypes.CAN_BE_AUTOMATED);

		RegistryAccess registryAccess = level.registryAccess();

		if (smeltingRecipe.isPresent())
			return null;

		RECIPE_WRAPPER.setItem(0, stack);
		Optional<BlastingRecipe> blastingRecipe = level.getRecipeManager()
				.getRecipeFor(RecipeType.BLASTING, RECIPE_WRAPPER, level)
				.filter(AllRecipeTypes.CAN_BE_AUTOMATED);

		if (blastingRecipe.isPresent())
			return blastingRecipe.get().getResultItem(registryAccess);

		return null;
	}

	@Nullable
	public List<ItemStack> process(ItemStack stack, Level level) {
		RECIPE_WRAPPER.setItem(0, stack);
		Optional<SmokingRecipe> smokingRecipe = level.getRecipeManager()
				.getRecipeFor(RecipeType.SMOKING, RECIPE_WRAPPER, level)
				.filter(AllRecipeTypes.CAN_BE_AUTOMATED);

		RECIPE_WRAPPER.setItem(0, stack);
		Optional<? extends AbstractCookingRecipe> smeltingRecipe = level.getRecipeManager()
				.getRecipeFor(RecipeType.SMELTING, RECIPE_WRAPPER, level)
				.filter(AllRecipeTypes.CAN_BE_AUTOMATED);

		if (!smeltingRecipe.isPresent()) {
			RECIPE_WRAPPER.setItem(0, stack);
			smeltingRecipe = level.getRecipeManager()
					.getRecipeFor(RecipeType.BLASTING, RECIPE_WRAPPER, level);
		}

		if (smeltingRecipe.isPresent()) {
			RegistryAccess registryAccess = level.registryAccess();
			if (!smokingRecipe.isPresent() || !ItemStack.isSameItem(smokingRecipe.get()
							.getResultItem(registryAccess),
					smeltingRecipe.get()
							.getResultItem(registryAccess))) {
				return RecipeApplier.applyRecipeOn(level, stack, smeltingRecipe.get());
			}
		}

		return Collections.emptyList();
	}
}
