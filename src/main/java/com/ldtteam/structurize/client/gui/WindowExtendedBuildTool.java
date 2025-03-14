package com.ldtteam.structurize.client.gui;

import com.ldtteam.blockui.Pane;
import com.ldtteam.blockui.PaneBuilders;
import com.ldtteam.blockui.controls.Button;
import com.ldtteam.blockui.controls.ButtonImage;
import com.ldtteam.blockui.controls.Text;
import com.ldtteam.blockui.util.resloc.OutOfJarResourceLocation;
import com.ldtteam.blockui.views.ScrollingList;
import com.ldtteam.blockui.views.View;
import com.ldtteam.structurize.Network;
import com.ldtteam.structurize.api.util.Log;
import com.ldtteam.structurize.blocks.interfaces.ILeveledBlueprintAnchorBlock;
import com.ldtteam.structurize.blocks.interfaces.INamedBlueprintAnchorBlock;
import com.ldtteam.structurize.blocks.interfaces.IRequirementsBlueprintAnchorBlock;
import com.ldtteam.structurize.blueprints.v1.Blueprint;
import com.ldtteam.structurize.blueprints.v1.BlueprintTagUtils;
import com.ldtteam.structurize.network.messages.BuildToolPlacementMessage;
import com.ldtteam.structurize.network.messages.SyncPreviewCacheToServer;
import com.ldtteam.structurize.storage.StructurePackMeta;
import com.ldtteam.structurize.storage.StructurePacks;
import com.ldtteam.structurize.storage.rendering.RenderingCache;
import com.ldtteam.structurize.storage.rendering.types.BlueprintPreviewData;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

import static com.ldtteam.structurize.api.util.constant.Constants.MOD_ID;
import static com.ldtteam.structurize.api.util.constant.GUIConstants.BUTTON_SWITCH_STYLE;
import static com.ldtteam.structurize.api.util.constant.GUIConstants.DEFAULT_ICON;
import static com.ldtteam.structurize.api.util.constant.WindowConstants.BUILD_TOOL_RESOURCE_SUFFIX;
import static com.ldtteam.structurize.api.util.constant.WindowConstants.BUTTON_CONFIRM;

/**
 * BuildTool window.
 */
public final class WindowExtendedBuildTool extends AbstractBlueprintManipulationWindow
{
    /**
     * Default block requirement check
     */
    public static final Predicate<Blueprint> BLOCK_BLUEPRINT_REQUIREMENT = blueprint ->
    {
        final BlockState anchor = blueprint.getBlockState(blueprint.getPrimaryBlockOffset());

        if (anchor.getBlock() instanceof final IRequirementsBlueprintAnchorBlock requirements)
        {
            return requirements.areRequirementsMet(Minecraft.getInstance().level,
              RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getPos(),
              Minecraft.getInstance().player);
        }
        else
        {
            return true;
        }
    };

    /**
     * Folder scrolling list.
     */
    private ScrollingList folderList;

    /**
     * Blueprint scrolling list.
     */
    private ScrollingList blueprintList;

    /**
     * Alternatives scrolling list.
     */
    private ScrollingList alternativesList;

    /**
     * Levels scrolling list.
     */
    private ScrollingList levelsList;

    /**
     * Current selected structure pack.
     */
    private static StructurePackMeta structurePack = null;

    /**
     * Next depth to open.
     */
    private static String nextDepth = "";

    /**
     * Current depth to display.
     */
    private static String depth = "";

    /**
     * List of categories at the current depth.
     */
    private static Future<List<StructurePacks.Category>> categoryFutures = null;

    /**
     * Next level of depth categories.
     */
    private static final Map<String, Future<List<StructurePacks.Category>>> nextDepthMeta = new LinkedHashMap<>();

    /**
     * Blueprints at depth.
     */
    private static final Map<String, Future<List<Blueprint>>> blueprintsAtDepth = new LinkedHashMap<>();

    /**
     * Current blueprint mapping from depth to processed blueprints.
     * Depth -> Named -> Leveled.
     */
    private static final Map<String, Map<String, Map<String, List<Blueprint>>>> currentBluePrintMappingAtDepthCache = new LinkedHashMap<>();

    /**
     * Current blueprint category.
     */
    private static String currentBlueprintCat = "";

    /**
     * Callback for when a schematic gets selected
     */
    private final BiConsumer<WindowExtendedBuildTool, Blueprint> selectionCallback;

    /**
     * Currently selected blueprint
     */
    public Blueprint selectedBlueprint= null;

    /**
     * Predicate dictating which blueprints are shown
     */
    private final Predicate<Blueprint> availableBlueprintPredicate;

    /**
     * Type of button.
     */
    public enum ButtonType
    {
        Blueprint,
        SubCategory,
        Back
    }

    public WindowExtendedBuildTool(
      final BlockPos pos,
      final int groundstyle)
    {
        this(pos, groundstyle, null, BLOCK_BLUEPRINT_REQUIREMENT);
    }

    /**
     * Creates a window build tool.
     * This requires X, Y and Z coordinates.
     * If a structure is active, recalculates the X Y Z with offset.
     * Otherwise the given parameters are used.
     *
     * @param pos                         coordinate.
     * @param groundstyle                 one of the GROUNDSTYLE_ values.
     * @param selectionCallback           callback which gets triggered on confirm clicked if a blueprint is selected
     * @param availableBlueprintPredicate
     */
    public WindowExtendedBuildTool(
      final BlockPos pos,
      final int groundstyle,
      @Nullable final BiConsumer<WindowExtendedBuildTool, Blueprint> selectionCallback,
      @Nullable final Predicate<Blueprint> availableBlueprintPredicate)
    {
        super(MOD_ID + BUILD_TOOL_RESOURCE_SUFFIX, pos, groundstyle, "blueprint");
        this.selectionCallback = selectionCallback;
        this.availableBlueprintPredicate = availableBlueprintPredicate;
        this.init(groundstyle, pos);
    }

    /**
     * On clicking confirm
     */
    @Override
    protected void confirmClicked()
    {
        if (selectionCallback == null)
        {
            super.confirmClicked();
            return;
        }

        selectionCallback.accept(this, selectedBlueprint);
    }

    @SuppressWarnings("resource")
    private void init(final int groundstyle, final BlockPos pos)
    {
        this.groundstyle = groundstyle;

        if (StructurePacks.selectedPack == null)
        {
            if (StructurePacks.getPackMetas().isEmpty())
            {
                return;
            }

            StructurePacks.selectedPack = StructurePacks.getPackMetas().iterator().next();
        }

        if (structurePack != null && !structurePack.getName().equals(StructurePacks.selectedPack.getName()))
        {
            depth = "";
            currentBluePrintMappingAtDepthCache.clear();
            blueprintsAtDepth.clear();
            nextDepthMeta.clear();
            categoryFutures = null;
            nextDepth = "";
            currentBlueprintCat = "";
            RenderingCache.removeBlueprint("blueprint");
            if (pos != null && RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getPos() == null)
            {
                RenderingCache.getOrCreateBlueprintPreviewData("blueprint").setPos(pos);
                adjustToGroundOffset();
            }
        }

        structurePack = StructurePacks.selectedPack;

        registerButton(BUTTON_SWITCH_STYLE, this::switchPackClicked);

        folderList = findPaneOfTypeByID("subcategories", ScrollingList.class);
        blueprintList = findPaneOfTypeByID("blueprints", ScrollingList.class);
        alternativesList = findPaneOfTypeByID("alternatives", ScrollingList.class);
        levelsList = findPaneOfTypeByID("levels", ScrollingList.class);

        if (depth.isEmpty())
        {
            findPaneOfTypeByID("tree", Text.class).setText(Component.literal(structurePack.getName()).setStyle(Style.EMPTY.withBold(true)));
        }
        else
        {
            findPaneOfTypeByID("tree", Text.class).setText(Component.literal(
                structurePack.getName()
                  + "/"
                  + depth
                  + (RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint() == null
                       ? ""
                       : ("/" + RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint().getFileName())))
              .setStyle(Style.EMPTY.withBold(true)));
        }
        categoryFutures = StructurePacks.getCategoriesFuture(structurePack.getName(), "");
        findPaneOfTypeByID("manipulator", View.class).setVisible(RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint() != null);

        if (!currentBlueprintCat.isEmpty())
        {
            final String up = currentBlueprintCat.substring(0, currentBlueprintCat.lastIndexOf(":"));
            handleBlueprintCategory(up.contains(":") ? up : currentBlueprintCat, true);
        }
        updateRotationState();
    }

    @Override
    public void onOpened()
    {
        if (structurePack == null)
        {
            Minecraft.getInstance().player.displayClientMessage(Component.translatable("structurize.pack.none"), false);
            close();
            return;
        }

        super.onOpened();
    }

    /**
     * Opens the switch style window.
     */
    private void switchPackClicked()
    {
        new WindowSwitchPack(() -> new WindowExtendedBuildTool(RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getPos(),
          groundstyle,
          selectionCallback,
          availableBlueprintPredicate)).open();
    }

    @Override
    protected void cancelClicked()
    {
        BlueprintPreviewData previewData = RenderingCache.removeBlueprint("blueprint");
        previewData.setBlueprint(null);
        previewData.setPos(BlockPos.ZERO);
        Network.getNetwork().sendToServer(new SyncPreviewCacheToServer(previewData));


        close();
        currentBlueprintCat = "";
        depth = "";
    }

    @Override
    protected void hideOtherGuiForPlacement()
    {
        super.hideOtherGuiForPlacement();

        blueprintList.disable();
        blueprintList.hide();
        folderList.hide();
        folderList.disable();
        alternativesList.hide();
        alternativesList.disable();
        levelsList.hide();
        levelsList.disable();
    }

    @Override
    protected void handlePlacement(final BuildToolPlacementMessage.HandlerType type, final String id)
    {
        final BlueprintPreviewData previewData = RenderingCache.getOrCreateBlueprintPreviewData("blueprint");
        if (previewData.getBlueprint() != null)
        {
            Network.getNetwork()
                    .sendToServer(new BuildToolPlacementMessage(type,
                            id,
                            structurePack.getName(),
                            structurePack.getSubPath(previewData.getBlueprint().getFilePath().resolve(previewData.getBlueprint().getFileName() + ".blueprint")),
                            previewData.getPos(),
                            previewData.getRotationMirror().rotation(),
                            previewData.getRotationMirror().mirror()));
            if (type == BuildToolPlacementMessage.HandlerType.Survival)
            {
                cancelClicked();
            }
        }
    }

    @Override
    public void onUpdate()
    {
        super.onUpdate();
        if (categoryFutures != null && categoryFutures.isDone())
        {
            final View categoryView = findPaneOfTypeByID("categories", View.class);
            if (!categoryView.getChildren().isEmpty())
            {
                categoryView.getChildren().clear();
            }
            try
            {
                int index = 0;
                for (final StructurePacks.Category category : categoryFutures.get())
                {
                    final ButtonImage img = new ButtonImage();
                    if (category.hasIcon)
                    {
                        try
                        {
                            img.setImage(OutOfJarResourceLocation.of(MOD_ID, category.packMeta.getPath().resolve(category.subPath).resolve("icon.png")), false);
                            img.setImageDisabled(OutOfJarResourceLocation.of(MOD_ID, category.packMeta.getPath().resolve(category.subPath).resolve("icon_disabled.png")), false);
                        }
                        catch (final Exception ex)
                        {
                            img.setImage(new ResourceLocation(DEFAULT_ICON), false);
                        }
                    }
                    else
                    {
                        img.setImage(new ResourceLocation(DEFAULT_ICON), false);
                    }

                    final String id = category.subPath;
                    img.setSize(19, 19);
                    img.setPosition(index * 20, 0);
                    img.setID(id);

                    categoryView.addChild(img);
                    PaneBuilders.tooltipBuilder().hoverPane(img).build().setText(Component.literal(id.substring(0, 1).toUpperCase(Locale.US) + id.substring(1)));

                    if (category.isTerminal)
                    {
                        blueprintsAtDepth.put(id, StructurePacks.getBlueprintsFuture(structurePack.getName(), id));
                    }
                    else
                    {
                        nextDepthMeta.put(id, StructurePacks.getCategoriesFuture(structurePack.getName(), id));
                    }

                    index++;
                }
            }
            catch (InterruptedException | ExecutionException e)
            {
                e.printStackTrace();
            }
            categoryFutures = null;
        }

        if (!nextDepth.isEmpty() && nextDepthMeta.containsKey(nextDepth))
        {
            final Future<List<StructurePacks.Category>> subCategories = nextDepthMeta.get(nextDepth);
            if (subCategories.isDone())
            {
                try
                {
                    final List<StructurePacks.Category> subCats = subCategories.get();
                    if (subCats.isEmpty())
                    {
                        nextDepthMeta.remove(nextDepth);
                        blueprintsAtDepth.put(nextDepth, StructurePacks.getBlueprintsFuture(id, nextDepth));
                    }
                    else
                    {
                        for (final StructurePacks.Category subCat : subCats)
                        {
                            final String id = subCat.subPath;
                            if (subCat.isTerminal)
                            {
                                blueprintsAtDepth.put(id, StructurePacks.getBlueprintsFuture(structurePack.getName(), id));
                            }
                            else
                            {
                                nextDepthMeta.put(id, StructurePacks.getCategoriesFuture(structurePack.getName(), id));
                            }
                        }
                        updateFolders(subCats, null);
                        nextDepth = "";
                    }
                }
                catch (final Exception ex)
                {
                    Log.getLogger().error("Something happened when loading subcategories", ex);
                }
            }
        }
        else if (!nextDepth.isEmpty() && blueprintsAtDepth.containsKey(nextDepth))
        {
            final Future<List<Blueprint>> blueprints = blueprintsAtDepth.get(nextDepth);
            if (blueprints.isDone())
            {
                try
                {
                    updateBlueprints(blueprints.get(), nextDepth);
                    nextDepth = "";
                }
                catch (InterruptedException | ExecutionException e)
                {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void settingsClicked()
    {
        super.settingsClicked();
        folderList.disable();
        folderList.hide();
        blueprintList.disable();
        blueprintList.hide();

        final View categories = findPaneOfTypeByID("categories", View.class);
        if (settingsList.isVisible())
        {
            categories.off();
        }
        else
        {
            categories.on();
            for (final Pane pane : categories.getChildren())
            {
                pane.enable();
            }
        }
    }

    /**
     * Update the sub category structure.
     *
     * @param inputCategories the categories to render now.
     */
    public void updateFolders(final List<StructurePacks.Category> inputCategories, final String prevCat)
    {
        folderList.enable();
        folderList.show();
        blueprintList.disable();
        blueprintList.hide();
        hidePlacementGui();

        final List<ButtonData> categories = new ArrayList<>();
        if (!inputCategories.isEmpty())
        {
            String parentCat = "";
            if (nextDepth.contains("/"))
            {
                final String[] split = nextDepth.split("/");
                final String currentCat = split[split.length - 1].equals("") ? split[split.length - 2] : split[split.length - 1];
                parentCat = nextDepth.replace("/" + currentCat, "");
            }
            categories.add(new ButtonData(ButtonType.Back, parentCat));
        }
        else if (prevCat != null)
        {
            categories.add(new ButtonData(ButtonType.Back, prevCat));
        }

        for (final StructurePacks.Category category : inputCategories)
        {
            categories.add(new ButtonData(ButtonType.SubCategory, category));
        }

        if (categories.size() <= 3)
        {
            folderList.setSize(270, 20);
            folderList.setPosition(100, 180);
        }
        else if (categories.size() > 6)
        {
            folderList.setSize(270, 60);
            folderList.setPosition(100, 140);
        }
        else
        {
            folderList.setSize(270, 40);
            folderList.setPosition(100, 160);
        }

        folderList.setDataProvider(new ScrollingList.DataProvider()
        {
            /**
             * The number of rows of the list.
             * @return the number.
             */
            @Override
            public int getElementCount()
            {
                return (int) Math.ceil(categories.size() / 3.0);
            }

            /**
             * Inserts the elements into each row.
             * @param index the index of the row/list element.
             * @param rowPane the parent Pane for the row, containing the elements to update.
             */
            @SuppressWarnings("resource")
            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                for (int i = 1; i <= 3; i++)
                {
                    int catIndex = index * 3 + i - 1;
                    if (categories.size() > catIndex)
                    {
                        handleSubCat(categories.get(catIndex), rowPane, i);
                    }
                }
            }
        });
    }

    /**
     * Update the displayed blueprints.
     *
     * @param inputBluePrints the blueprints to display.
     * @param depth           the depth they're at.
     */
    public void updateBlueprints(final List<Blueprint> inputBluePrints, final String depth)
    {
        blueprintList.enable();
        blueprintList.show();
        folderList.disable();
        folderList.hide();
        hidePlacementGui();

        final List<ButtonData> blueprints = new ArrayList<>();
        if (!inputBluePrints.isEmpty())
        {
            String parentCat = "";
            if (depth.contains("/"))
            {
                final String[] split = depth.split("/");
                final String currentCat = split[split.length - 1].equals("") ? split[split.length - 2] : split[split.length - 1];
                parentCat = depth.replace("/" + currentCat, "");
            }
            blueprints.add(new ButtonData(ButtonType.Back, parentCat));
        }

        final Map<String, List<Blueprint>> blueprintMapping = new LinkedHashMap<>();

        for (final Blueprint blueprint : inputBluePrints)
        {
            final BlockState anchor = blueprint.getBlockState(blueprint.getPrimaryBlockOffset());
            if (!Minecraft.getInstance().player.isCreative() && BlueprintTagUtils.isInvisible(blueprint))
            {
                continue;
            }

            final String name;
            if (anchor.getBlock() instanceof ILeveledBlueprintAnchorBlock leveledAnchor)
            {
                final int level =
                  leveledAnchor.getLevel(blueprint.getTileEntityData(RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getPos(),
                    blueprint.getPrimaryBlockOffset()));
                final String tempName = getCustomName(blueprint, blueprint.getFileName().replace(Integer.toString(level), ""));
                name = tempName.isEmpty() ? getCustomName(blueprint, blueprint.getFileName()) : tempName;
            }
            else
            {
                name = getCustomName(blueprint, blueprint.getFileName());
            }

            final List<Blueprint> blueprintList = blueprintMapping.getOrDefault(name, new ArrayList<>());
            blueprintList.add(blueprint);
            blueprintMapping.put(name, blueprintList);
        }

        final Map<String, Map<String, List<Blueprint>>> altBlueprintMapping = new LinkedHashMap<>();

        for (final Map.Entry<String, List<Blueprint>> entry : blueprintMapping.entrySet())
        {
            final Blueprint blueprint = entry.getValue().get(0);
            final BlockState anchor = blueprint.getBlockState(blueprint.getPrimaryBlockOffset());
            final String name;
            if (anchor.getBlock() instanceof INamedBlueprintAnchorBlock)
            {
                name = anchor.getBlock().getDescriptionId();
            }
            else
            {
                name = entry.getKey();
            }

            final Map<String, List<Blueprint>> tempLeveledBlueprints = altBlueprintMapping.getOrDefault(name, new LinkedHashMap<>());
            tempLeveledBlueprints.put(entry.getKey(), entry.getValue());
            altBlueprintMapping.put(name, tempLeveledBlueprints);
        }

        currentBluePrintMappingAtDepthCache.put(depth, altBlueprintMapping);

        for (final Map.Entry<String, Map<String, List<Blueprint>>> entry : altBlueprintMapping.entrySet())
        {
            blueprints.add(new ButtonData(ButtonType.Blueprint, entry.getKey()));
        }

        if (blueprints.size() <= 3)
        {
            blueprintList.setSize(270, 20);
            blueprintList.setPosition(100, 180);
        }
        else if (blueprints.size() > 6)
        {
            blueprintList.setSize(270, 60);
            blueprintList.setPosition(100, 140);
        }
        else
        {
            blueprintList.setSize(270, 40);
            blueprintList.setPosition(100, 160);
        }

        blueprintList.setDataProvider(new ScrollingList.DataProvider()
        {
            /**
             * The number of rows of the list.
             * @return the number.
             */
            @Override
            public int getElementCount()
            {
                return (int) Math.ceil(blueprints.size() / 3.0);
            }

            /**
             * Inserts the elements into each row.
             * @param index the index of the row/list element.
             * @param rowPane the parent Pane for the row, containing the elements to update.
             */
            @SuppressWarnings("resource")
            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                for (int i = 1; i <= 3; i++)
                {
                    int catIndex = index * 3 + i - 1;
                    if (blueprints.size() > catIndex)
                    {
                        handleBlueprint(blueprints.get(catIndex), rowPane, i, depth);
                    }
                }
            }
        });
    }

    /**
     * Update the alternative blueprint list.
     *
     * @param bluePrintMapping the mapping of blueprint name to leveled blueprints.
     */
    public void updateAlternatives(final Map<String, List<Blueprint>> bluePrintMapping, final String depth)
    {
        alternativesList.enable();
        alternativesList.show();
        levelsList.hide();
        levelsList.disable();
        settingsList.hide();
        settingsList.disable();

        final List<Map.Entry<String, List<Blueprint>>> list = new ArrayList<>(bluePrintMapping.entrySet());

        alternativesList.setDataProvider(new ScrollingList.DataProvider()
        {
            /**
             * The number of rows of the list.
             * @return the number.
             */
            @Override
            public int getElementCount()
            {
                return list.size();
            }

            /**
             * Inserts the elements into each row.
             * @param index the index of the row/list element.
             * @param rowPane the parent Pane for the row, containing the elements to update.
             */
            @SuppressWarnings("resource")
            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                final ButtonImage button = rowPane.findPaneOfTypeByID("alternative", ButtonImage.class);
                rowPane.findPaneOfTypeByID("id", Text.class).setText(Component.literal(depth + ":" + list.get(index).getKey()));
                button.setText(Component.literal(list.get(index).getKey()));
                button.setTextColor(ChatFormatting.BLACK.getColor());
            }
        });
    }

    /**
     * Update the alternative blueprint list.
     *
     * @param blueprints the different blueprint levels.
     */
    public void updateLevels(final List<Blueprint> blueprints, final String depth, final boolean hasAlternatives)
    {
        levelsList.enable();
        levelsList.show();
        alternativesList.hide();
        alternativesList.disable();
        settingsList.hide();
        settingsList.disable();

        if (hasAlternatives)
        {
            blueprints.add(0, null);
        }

        levelsList.setDataProvider(new ScrollingList.DataProvider()
        {
            /**
             * The number of rows of the list.
             * @return the number.
             */
            @Override
            public int getElementCount()
            {
                return blueprints.size();
            }

            /**
             * Inserts the elements into each row.
             * @param index the index of the row/list element.
             * @param rowPane the parent Pane for the row, containing the elements to update.
             */
            @SuppressWarnings("resource")
            @Override
            public void updateElement(final int index, final Pane rowPane)
            {
                if (blueprints.get(index) == null)
                {
                    final String buttonId = depth.substring(0, depth.lastIndexOf(":")) + ":back";
                    final ButtonImage button = rowPane.findPaneOfTypeByID("level", ButtonImage.class);
                    rowPane.findPaneOfTypeByID("id", Text.class).setText(Component.literal(buttonId));
                    button.setText(Component.literal(""));
                    button.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/back_medium.png"), false);
                }
                else
                {
                    final String buttonId = depth + ":" + (hasAlternatives ? index - 1 : index);
                    final ButtonImage button = rowPane.findPaneOfTypeByID("level", ButtonImage.class);
                    rowPane.findPaneOfTypeByID("id", Text.class).setText(Component.literal(buttonId));
                    button.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/button_medium.png"), false);
                    button.setText(Component.literal("Level: " + (index + (hasAlternatives ? 0 : 1))));
                    button.setTextColor(ChatFormatting.BLACK.getColor());
                }
            }
        });
    }

    private void handleBlueprint(final ButtonData buttonData, final Pane rowPane, final int index, final String depth)
    {
        ButtonImage img = rowPane.findPaneOfTypeByID(Integer.toString(index), ButtonImage.class);
        if (buttonData.type == ButtonType.Back)
        {
            if (img == null)
            {
                img = rowPane.findPaneOfTypeByID("back:" + buttonData.data, ButtonImage.class);
            }
            img.setID("back:" + buttonData.data);
            img.setVisible(true);
            img.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/back_medium.png"), false);
            PaneBuilders.tooltipBuilder().hoverPane(img).build().setText(Component.literal("back"));
        }
        else if (buttonData.type == ButtonType.Blueprint)
        {
            final String id = (String) buttonData.data;
            if (img == null)
            {
                img = rowPane.findPaneOfTypeByID(depth + ":" + id, ButtonImage.class);
            }

            if (img == null)
            {
                return;
            }
            img.setID(depth + ":" + id);

            final Map<String, List<Blueprint>> blueprintMap = currentBluePrintMappingAtDepthCache.get(depth).get(id);
            final Blueprint firstBlueprint = blueprintMap.values().iterator().next().get(0);

            final BlockState anchor = firstBlueprint.getBlockState(firstBlueprint.getPrimaryBlockOffset());
            final List<MutableComponent> toolTip = new ArrayList<>();
            if (anchor.getBlock() instanceof INamedBlueprintAnchorBlock namedBlock)
            {
                img.setText(namedBlock.getBlueprintDisplayName());
                toolTip.addAll(namedBlock.getDesc());
            }
            else
            {
                final String name = getCustomName(firstBlueprint, firstBlueprint.getFileName());
                img.setText(Component.literal(name));
                toolTip.add(Component.literal(name));
            }
            img.setVisible(true);

            boolean hasMatch = false;
            for (final List<Blueprint> blueprints : blueprintMap.values())
            {
                for (final Blueprint blueprint : blueprints)
                {
                    if (blueprint.equals(RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint()))
                    {
                        hasMatch = true;
                        break;
                    }
                }
            }

            boolean hasAlts = blueprintMap.values().size() > 1;
            boolean isInvis = BlueprintTagUtils.isInvisible(firstBlueprint);
            boolean isLocked = false;

            if (availableBlueprintPredicate != null && !availableBlueprintPredicate.test(firstBlueprint))
            {
                if (anchor.getBlock() instanceof final IRequirementsBlueprintAnchorBlock requirements)
                {
                    toolTip.addAll(requirements.getRequirements(Minecraft.getInstance().level, RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getPos(), Minecraft.getInstance().player));
                }

                PaneBuilders.tooltipBuilder().hoverPane(img).build().setText(toolTip);
                img.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/button_blueprint_disabled" + (hasAlts ? "_variant" : "") + ".png"), false);
                isLocked = true;
            }

            PaneBuilders.tooltipBuilder().hoverPane(img).build().setText(toolTip);

            if (hasMatch)
            {
                img.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/button_blueprint_selected" + (isInvis ? "_creative" : "") + (hasAlts ? "_variant" : "") + ".png"),
                  false);
            }
            else if (!isLocked)
            {
                img.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/button_blueprint" + (isInvis ? "_creative" : "") + (hasAlts ? "_variant" : "") + ".png"), false);
            }
        }
    }

    /**
     * Determine the display names of the given blueprint.
     *
     * @param blueprint   the blueprint
     * @param defaultName the name to use if the blueprint doesn't have a custom name
     * @return            its custom name, or a default name
     */
    private String getCustomName(final Blueprint blueprint, final String defaultName)
    {
        final Map<BlockPos, List<String>> tagPosMap = BlueprintTagUtils.getBlueprintTags(blueprint);
        final Optional<String> nameTag = tagPosMap.getOrDefault(BlockPos.ZERO, new ArrayList<>()).stream()
                .filter(t -> t.startsWith("name=")).findFirst();

        return nameTag.map(s -> s.substring(5)).orElse(defaultName);
    }

    private void handleSubCat(final ButtonData buttonData, final Pane rowPane, final int index)
    {
        ButtonImage img = rowPane.findPaneOfTypeByID(Integer.toString(index), ButtonImage.class);
        if (buttonData.type == ButtonType.Back)
        {
            if (img == null)
            {
                img = rowPane.findPaneOfTypeByID("back:" + buttonData.data, ButtonImage.class);
            }
            img.setID("back:" + buttonData.data);
            img.setVisible(true);
            img.setImage(new ResourceLocation(MOD_ID, "textures/gui/buildtool/back_medium.png"), false);
            PaneBuilders.tooltipBuilder().hoverPane(img).build().setText(Component.literal("back"));
            return;
        }

        final StructurePacks.Category subCat = (StructurePacks.Category) buttonData.data;
        final String id = subCat.subPath;

        if (img == null)
        {
            img = rowPane.findPaneOfTypeByID(id, ButtonImage.class);
        }

        img.setID(id);
        String descString = id.split("/")[id.split("/").length - 1];
        descString = descString.substring(0, 1).toUpperCase(Locale.US) + descString.substring(1);
        final Component desc = Component.literal(descString);
        img.setText(desc);
        img.setVisible(true);
        img.setTextColor(ChatFormatting.BLACK.getColor());
    }

    @Override
    public void onButtonClicked(final Button button)
    {
        boolean handled = false;
        if (button.getID().contains("back:"))
        {
            nextDepth = button.getID().split(":").length == 1 ? "" : button.getID().split(":")[1];
            updateFolders(Collections.emptyList(), null);
            updateBlueprints(Collections.emptyList(), "");
            depth = nextDepth;
            if (depth.isEmpty())
            {
                for (final Pane pane : findPaneOfTypeByID("categories", View.class).getChildren())
                {
                    pane.enable();
                }
            }
            findPaneOfTypeByID("tree", Text.class).setText(Component.literal(structurePack.getName() + "/" + nextDepth).setStyle(Style.EMPTY.withBold(true)));
            button.setHoverPane(null);
            handled = true;
        }
        else if (nextDepthMeta.containsKey(button.getID()))
        {
            nextDepth = button.getID();
            updateFolders(Collections.emptyList(), null);
            updateBlueprints(Collections.emptyList(), "");
            depth = nextDepth;
            findPaneOfTypeByID("tree", Text.class).setText(Component.literal(structurePack.getName() + "/" + nextDepth).setStyle(Style.EMPTY.withBold(true)));
            if (nextDepth.contains("/"))
            {
                button.setHoverPane(null);
            }
            else
            {
                for (final Pane pane : findPaneOfTypeByID("categories", View.class).getChildren())
                {
                    pane.enable();
                }
                button.disable();
            }
            handled = true;
        }
        else if (blueprintsAtDepth.containsKey(button.getID()))
        {
            nextDepth = button.getID();
            updateFolders(Collections.emptyList(), null);
            updateBlueprints(Collections.emptyList(), "");
            depth = nextDepth;
            findPaneOfTypeByID("tree", Text.class).setText(Component.literal(structurePack.getName() + "/" + nextDepth).setStyle(Style.EMPTY.withBold(true)));
            if (nextDepth.contains("/"))
            {
                button.setHoverPane(null);
            }
            else
            {
                for (final Pane pane : findPaneOfTypeByID("categories", View.class).getChildren())
                {
                    pane.enable();
                }
                button.disable();
            }
            handled = true;
        }
        else if (button.getID().contains(":"))
        {
            for (final Pane pane : findPaneOfTypeByID("categories", View.class).getChildren())
            {
                pane.enable();
            }

            currentBlueprintCat = button.getID().replace(":back", "");
            handleBlueprintCategory(currentBlueprintCat, false);
            button.setHoverPane(null);
            handled = true;
        }
        else if (button.getID().equals("alternative") || button.getID().equals("level"))
        {
            for (final Pane pane : findPaneOfTypeByID("categories", View.class).getChildren())
            {
                pane.enable();
            }

            currentBlueprintCat = button.getParent().findPaneOfTypeByID("id", Text.class).getText().getString().replace(":back", "");
            handleBlueprintCategory(currentBlueprintCat, false);
            button.setHoverPane(null);
            handled = true;
        }

        findPaneOfTypeByID("manipulator", View.class).setVisible(RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint() != null);

        if (!handled)
        {
            super.onButtonClicked(button);
        }
    }

    private void handleBlueprintCategory(final String categoryId, final boolean onOpen)
    {
        final String[] split = categoryId.split(":");
        final String id = split[1];
        final Map<String, List<Blueprint>> mapping = currentBluePrintMappingAtDepthCache.get(split[0]).get(id);
        if (mapping == null)
        {
            Log.getLogger().error("Invalid blueprint name at depth: " + categoryId);
            return;
        }

        if (split.length == 2)
        {
            if (mapping.size() == 1 && mapping.values().iterator().next().size() == 1)
            {
                alternativesList.hide();
                alternativesList.disable();
                levelsList.hide();
                levelsList.disable();

                setBlueprint(mapping.values().iterator().next().get(0));
                return;
            }

            if (mapping.size() > 1)
            {
                updateLevels(Collections.emptyList(), "", false);
                updateAlternatives(mapping, categoryId);
            }
            else
            {
                updateAlternatives(Collections.emptyMap(), categoryId);
                final List<Blueprint> leveled = mapping.values().iterator().next();

                if (RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint() == null || !onOpen)
                {
                    setBlueprint(leveled.get(0));
                }
                updateLevels(leveled, categoryId + ":" + mapping.keySet().iterator().next(), false);
            }
        }
        else if (split.length == 3)
        {
            final List<Blueprint> list = mapping.get(split[2]);
            if (list == null || list.isEmpty())
            {
                Log.getLogger().error("Invalid blueprint name at depth: " + categoryId);
                return;
            }

            if (RenderingCache.getOrCreateBlueprintPreviewData("blueprint").getBlueprint() == null || list.size() == 1 || !onOpen)
            {
                setBlueprint(list.get(0));
            }

            if (list.size() == 1)
            {
                return;
            }

            updateAlternatives(Collections.emptyMap(), categoryId);
            updateLevels(new ArrayList<>(list), categoryId, mapping.size() > 1);
        }
        else if (split.length == 4)
        {
            final List<Blueprint> list = mapping.get(split[2]);
            if (list == null || list.isEmpty())
            {
                Log.getLogger().error("Invalid blueprint name at depth: " + categoryId);
                return;
            }

            try
            {
                int level = Integer.parseInt(split[3]);
                setBlueprint(list.get(level));
                return;
            }
            catch (final NumberFormatException exception)
            {
                Log.getLogger().error("Invalid blueprint name at depth: " + categoryId);
            }
        }
        else
        {
            Log.getLogger().error("Invalid blueprint name at depth: " + categoryId);
        }
        updateFolders(Collections.emptyList(), split[0]);
    }

    private void setBlueprint(Blueprint blueprint)
    {
        final BlueprintPreviewData data = RenderingCache.getOrCreateBlueprintPreviewData("blueprint");

        findPaneOfTypeByID("tree", Text.class).setText(Component.literal(structurePack.getName() + "/" + depth + "/" + blueprint.getFileName()).setStyle(Style.EMPTY.withBold(true)));
        data.setBlueprint(blueprint);
        adjustToGroundOffset();
        selectedBlueprint = blueprint;

        final boolean canBuild = availableBlueprintPredicate == null || availableBlueprintPredicate.test(blueprint);

        findPaneOfTypeByID(BUTTON_CONFIRM, Button.class).setVisible(canBuild);
    }

    /**
     * Button Data.
     */
    public static class ButtonData
    {
        public ButtonType type;
        public Object     data;

        public ButtonData(ButtonType type, Object data)
        {
            this.type = type;
            this.data = data;
        }
    }
}
