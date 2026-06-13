package com.deathfrog.tradepostapo.compat.recycling;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;

import com.deathfrog.mctradepost.compat.recycling.IOptionalRecyclingProvider;
import com.deathfrog.mctradepost.compat.recycling.RecyclingPlan;
import com.deathfrog.tradepostapo.TradePostAPOCompat;

import dev.shadowsoffire.apotheosis.Apoth;
import dev.shadowsoffire.apotheosis.affix.salvaging.SalvagingRecipe;
import dev.shadowsoffire.apotheosis.affix.salvaging.SalvagingRecipe.OutputData;
import dev.shadowsoffire.apotheosis.socket.SocketHelper;
import dev.shadowsoffire.apotheosis.socket.gem.GemInstance;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.level.Level;

/**
 * Recycler integration for Apotheosis salvaging and socketed gem withdrawal.
 */
public final class ApotheosisRecyclingProvider implements IOptionalRecyclingProvider
{
    private static final Logger LOGGER = TradePostAPOCompat.LOGGER;
    private static final double ADVANCED_RECYCLING_RESEARCH_LEVEL = .40;

    @Override
    @Nullable
    public RecyclingPlan tryResolve(final ItemStack input, final Level level, final int workerSkill, final double researchLevel)
    {
        if (input.isEmpty())
        {
            return null;
        }

        final RecipeManager recipeManager = level.getRecipeManager();
        if (recipeManager == null)
        {
            return null;
        }

        final List<ItemStack> outputs = new ArrayList<>();
        final boolean recoveredSocketedGems = addSocketedGemOutputs(input, outputs);
        final List<ItemStack> salvageOutputs = getSalvageResults(level, input);
        outputs.addAll(copyItemStacks(salvageOutputs));

        final boolean recoveredApotheosisMaterials = salvageOutputs.stream().anyMatch(ApotheosisRecyclingProvider::isAdvancedSalvageOutput);
        final boolean requiresAdvancedResearch = recoveredSocketedGems || recoveredApotheosisMaterials;

        if (requiresAdvancedResearch && researchLevel < ADVANCED_RECYCLING_RESEARCH_LEVEL)
        {
            LOGGER.debug("Skipping Apotheosis recycling for {} because advanced recycling research is required.", input);
            return new RecyclingPlan.FinalOutputs(List.of());
        }

        if (outputs.isEmpty())
        {
            return null;
        }

        outputs.add(createBaseItemOutput(input));

        return new RecyclingPlan.FinalOutputs(outputs);
    }

    /**
     * Resolves the same recipe outputs that the Apotheosis Salvaging Table would produce for a single input stack.
     * <p>
     * This returns final item stacks because Apotheosis recipes already define randomized output ranges and durability scaling.
     *
     * @param level the level containing the runtime recipe manager and random source
     * @param input the item stack being salvaged
     * @return copied salvage result stacks, or an empty list when no Apotheosis salvaging recipe matches
     */
    @SuppressWarnings("null")
    private static List<ItemStack> getSalvageResults(final Level level, final ItemStack input)
    {
        final List<ItemStack> outputs = new ArrayList<>();
        for (final RecipeHolder<SalvagingRecipe> recipe : level.getRecipeManager().getRecipesFor(Apoth.RecipeTypes.SALVAGING, new SingleRecipeInput(input), level))
        {
            for (final OutputData outputData : recipe.value().getOutputs())
            {
                final ItemStack output = outputData.stack().copy();
                output.setCount(getSalvageCount(outputData, input, level));
                outputs.add(output);
            }
        }

        return outputs;
    }

    /**
     * Rolls the final count for a single Apotheosis salvage output.
     *
     * @param output the configured salvage output range
     * @param input the item stack being salvaged
     * @param level the level providing the random source
     * @return a random count in the durability-adjusted output range
     */
    private static int getSalvageCount(final OutputData output, final ItemStack input, final Level level)
    {
        final int[] counts = getSalvageCounts(output, input);
        return level.random.nextInt(counts[0], counts[1] + 1);
    }

    /**
     * Computes the Apotheosis Salvaging Table output range after applying durability scaling.
     * <p>
     * Damageable items reduce only the maximum count, preserving the recipe's minimum count.
     *
     * @param output the configured salvage output range
     * @param input the item stack being salvaged
     * @return a two-element array containing the minimum and maximum possible output count
     */
    private static int[] getSalvageCounts(final OutputData output, final ItemStack input)
    {
        final int[] counts = { output.min(), output.max() };
        if (input.isDamageableItem())
        {
            final int maxDamage = input.getMaxDamage();
            if (maxDamage <= 0)
            {
                LOGGER.warn("Item {} returned true to ItemStack#isDamageableItem, but returned {} from ItemStack#getMaxDamage.", input.getItemHolder().getKey(), maxDamage);
                return counts;
            }

            counts[1] = Math.max(counts[0], Math.round(counts[1] * (maxDamage - input.getDamageValue()) / (float) maxDamage));
        }

        return counts;
    }

    /**
     * Adds valid socketed gems to the output list, simulating the item-return portion of Apotheosis's Sigil of Withdrawal.
     * <p>
     * The base item is emitted separately after Apotheosis recovery has claimed the input stack.
     *
     * @param input the item stack being recycled
     * @param outputs the mutable final-output list to receive recovered gem stacks
     * @return {@code true} when at least one valid socketed gem was recovered
     */
    private static boolean addSocketedGemOutputs(final ItemStack input, final List<ItemStack> outputs)
    {
        boolean recoveredAny = false;
        for (final GemInstance gem : SocketHelper.getGems(input))
        {
            if (!gem.isValid())
            {
                continue;
            }

            final ItemStack gemStack = gem.gemStack().copy();
            gemStack.setCount(1);
            outputs.add(gemStack);
            recoveredAny = true;
        }

        return recoveredAny;
    }

    /**
     * Creates a vanilla-equivalent copy of the input item, preserving only damage.
     * Note that enchantments are not copied, as the base recycler logic will
     * handle those based on the input item.
     *
     * @param input the claimed Apotheosis stack
     * @return a fresh item stack with no Apotheosis-specific components
     */
    private static ItemStack createBaseItemOutput(final ItemStack input)
    {

        Item inputItem = input.getItem();

        if (inputItem == null) return ItemStack.EMPTY;

        final ItemStack baseItem = new ItemStack(inputItem, 1);
        
        if (input.isDamageableItem())
        {
            baseItem.setDamageValue(input.getDamageValue());
        }

        if (ItemStack.isSameItemSameComponents(input, baseItem)) return ItemStack.EMPTY;

        // copyComponent(input, baseItem, DataComponents.ENCHANTMENTS);
        
        return baseItem;
    }

    /**
     * Copies a single data component from one stack to another when present.
     *
     * @param source the stack to copy from
     * @param target the stack to copy to
     * @param component the component type to preserve
     * @param <T> the component value type
     */
    protected static <T> void copyComponent(final ItemStack source, final ItemStack target, final DataComponentType<T> component)
    {
        if (component == null) return;

        final T value = source.get(component);
        if (value != null)
        {
            target.set(component, value);
        }
    }

    /**
     * Identifies Apotheosis outputs that are gated behind advanced recycling research.
     *
     * @param output a candidate Apotheosis salvage output
     * @return {@code true} when the output is gem dust or an Apotheosis rarity material
     */
    @SuppressWarnings("null")
    private static boolean isAdvancedSalvageOutput(final ItemStack output)
    {
        return is(output, Apoth.Items.GEM_DUST)
            || is(output, Apoth.Items.COMMON_MATERIAL)
            || is(output, Apoth.Items.UNCOMMON_MATERIAL)
            || is(output, Apoth.Items.RARE_MATERIAL)
            || is(output, Apoth.Items.EPIC_MATERIAL)
            || is(output, Apoth.Items.MYTHIC_MATERIAL);
    }

    /**
     * Checks an item stack against an Apotheosis item holder.
     *
     * @param stack the stack to test
     * @param item the registered Apotheosis item holder
     * @return {@code true} when the stack item matches the holder value
     */
    private static boolean is(final ItemStack stack, final @Nonnull Holder<Item> item)
    {
        Item itemVal = item.value();

        if (itemVal == null) return false;

        return stack.is(itemVal);
    }

    /**
     * Copies non-empty item stacks for safe insertion into a final recycling plan.
     *
     * @param stacks the stacks to copy
     * @return a new list containing copies of all non-empty stacks
     */
    private static List<ItemStack> copyItemStacks(final List<ItemStack> stacks)
    {
        final List<ItemStack> copies = new ArrayList<>();
        for (final ItemStack stack : stacks)
        {
            if (!stack.isEmpty())
            {
                copies.add(stack.copy());
            }
        }

        return copies;
    }
}
