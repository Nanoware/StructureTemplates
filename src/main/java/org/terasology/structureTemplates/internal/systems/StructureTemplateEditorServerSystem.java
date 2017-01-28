/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.structureTemplates.internal.systems;

import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.MutableComponentContainer;
import org.terasology.entitySystem.entity.EntityBuilder;
import org.terasology.entitySystem.entity.EntityManager;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.EventPriority;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.metadata.ComponentLibrary;
import org.terasology.entitySystem.metadata.EntitySystemLibrary;
import org.terasology.entitySystem.prefab.Prefab;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.logic.common.ActivateEvent;
import org.terasology.logic.health.DoDestroyEvent;
import org.terasology.logic.inventory.InventoryManager;
import org.terasology.math.Region3i;
import org.terasology.math.Side;
import org.terasology.math.geom.Vector3f;
import org.terasology.math.geom.Vector3i;
import org.terasology.network.ClientComponent;
import org.terasology.registry.In;
import org.terasology.structureTemplates.components.BlockPlaceholderComponent;
import org.terasology.structureTemplates.components.ScheduleStructurePlacementComponent;
import org.terasology.structureTemplates.components.SpawnBlockRegionsComponent;
import org.terasology.structureTemplates.components.SpawnBlockRegionsComponent.RegionToFill;
import org.terasology.structureTemplates.components.SpawnTemplateActionComponent;
import org.terasology.structureTemplates.components.StructureTemplateComponent;
import org.terasology.structureTemplates.events.BuildStructureTemplateEntityEvent;
import org.terasology.structureTemplates.events.SpawnTemplateEvent;
import org.terasology.structureTemplates.internal.components.EditsCopyRegionComponent;
import org.terasology.structureTemplates.internal.components.StructurePlaceholderComponent;
import org.terasology.structureTemplates.internal.events.BuildStructureTemplateStringEvent;
import org.terasology.structureTemplates.internal.events.CopyBlockRegionResultEvent;
import org.terasology.structureTemplates.internal.events.CreateStructureSpawnItemRequest;
import org.terasology.structureTemplates.internal.events.MakeBoxShapedRequest;
import org.terasology.structureTemplates.internal.events.RequestStructurePlaceholderPrefabSelection;
import org.terasology.structureTemplates.internal.events.RequestStructureTemplatePropertiesChange;
import org.terasology.structureTemplates.internal.events.StructureTemplateStringRequest;
import org.terasology.structureTemplates.util.ListUtil;
import org.terasology.structureTemplates.util.RegionMergeUtil;
import org.terasology.structureTemplates.util.transform.BlockRegionMovement;
import org.terasology.structureTemplates.util.transform.BlockRegionTransform;
import org.terasology.structureTemplates.util.transform.BlockRegionTransformationList;
import org.terasology.structureTemplates.util.transform.HorizontalBlockRegionRotation;
import org.terasology.world.BlockEntityRegistry;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.BlockManager;
import org.terasology.world.block.entity.placement.PlaceBlocks;
import org.terasology.world.block.family.BlockFamily;
import org.terasology.world.block.family.HorizontalBlockFamily;
import org.terasology.world.block.items.BlockItemComponent;
import org.terasology.world.block.items.OnBlockItemPlaced;
import org.terasology.world.block.items.OnBlockToItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles the activation of the copyBlockRegionTool item.
 */
@RegisterSystem(RegisterMode.AUTHORITY)
public class StructureTemplateEditorServerSystem extends BaseComponentSystem {
    private static final Comparator<RegionToFill> REGION_BY_MIN_X_COMPARATOR = Comparator.comparing(r -> r.region.minX());
    private static final Comparator<RegionToFill> REGION_BY_MIN_Y_COMPARATOR = Comparator.comparing(r -> r.region.minY());
    private static final Comparator<RegionToFill> REGION_BY_MIN_Z_COMPARATOR = Comparator.comparing(r -> r.region.minZ());
    private static final Comparator<RegionToFill> REGION_BY_BLOCK_TYPE_COMPARATOR = Comparator.comparing(r -> r.blockType.getURI().toString());
    private static final Logger LOGGER = LoggerFactory.getLogger(StructureTemplateEditorServerSystem.class);

    @In
    private WorldProvider worldProvider;

    @In
    private BlockManager blockManager;

    @In
    private InventoryManager inventoryManager;

    @In
    private EntityManager entityManager;

    @In
    private EntitySystemLibrary entitySystemLibrary;

    @In
    private BlockEntityRegistry blockEntityRegistry;

    @In
    private ComponentLibrary componentLibrary;

    @ReceiveEvent
    public void onActivate(ActivateEvent event, EntityRef entity,
                           StructureTemplateGeneratorComponent structureTemplateEditorComponent) {
        BlockComponent blockComponent = event.getTarget().getComponent(BlockComponent.class);
        if (blockComponent == null) {
            return;
        }
        Vector3i position = blockComponent.getPosition();


        Vector3f directionVector = event.getDirection();
        Side directionStructureIsIn = Side.inHorizontalDirection(directionVector.getX(), directionVector.getZ());
        Side frontDirectionOfStructure = directionStructureIsIn.reverse();

        StructureTemplateComponent component = new StructureTemplateComponent();

        boolean originPlaced = placeOriginMarkerWithTemplateData(event, position, frontDirectionOfStructure,
                Lists.newArrayList(), component);
        if (!originPlaced) {
            return;
        }
    }

    private float sideToAngle(Side side) {
        switch (side) {
            case LEFT:
                return 0.5f * (float) Math.PI;
            case RIGHT:
                return -0.5f * (float) Math.PI;
            case BACK:
                return (float) Math.PI;
            default:
                return 0f;

        }
    }

    @ReceiveEvent
    public void updateCopyRegionOnBlockPlacement(PlaceBlocks placeBlocks, EntityRef world) {
        EntityRef player = placeBlocks.getInstigator().getOwner();
        EditsCopyRegionComponent editsCopyRegionComponent = player.getComponent(EditsCopyRegionComponent.class);
        if (editsCopyRegionComponent == null) {
            return;
        }
        EntityRef editorEnitity = editsCopyRegionComponent.structureTemplateEditor;
        StructureTemplateEditorComponent editorComponent = editorEnitity.getComponent(StructureTemplateEditorComponent.class);
        if (editorComponent == null) {
            return; // can happen if entity got destroyed
        }
        List<Region3i> originalRegions = editorComponent.absoluteRegionsWithTemplate;
        Set<Vector3i> positionsInTemplate = RegionMergeUtil.positionsOfRegions(originalRegions);
        if (positionsInTemplate.containsAll(placeBlocks.getBlocks().keySet())) {
            return; // nothing to do
        }
        positionsInTemplate.addAll(placeBlocks.getBlocks().keySet());
        List<Region3i> newTemplateRegions = RegionMergeUtil.mergePositionsIntoRegions(positionsInTemplate);
        editorComponent.absoluteRegionsWithTemplate = newTemplateRegions;
        editorEnitity.saveComponent(editorComponent);
    }


    @ReceiveEvent
    public void onDestroyed(DoDestroyEvent event, EntityRef entity, BlockComponent blockComponent) {
        EntityRef player = event.getInstigator().getOwner();
        EditsCopyRegionComponent editsCopyRegionComponent = player.getComponent(EditsCopyRegionComponent.class);
        if (editsCopyRegionComponent == null) {
            return;
        }

        EntityRef editorEnitity = editsCopyRegionComponent.structureTemplateEditor;
        StructureTemplateEditorComponent editorComponent = editorEnitity.getComponent(StructureTemplateEditorComponent.class);
        if (editorComponent == null) {
            return; // can happen if entity got destroyed
        }
        List<Region3i> originalRegions = editorComponent.absoluteRegionsWithTemplate;
        Set<Vector3i> positionsInTemplate = RegionMergeUtil.positionsOfRegions(originalRegions);
        if (!positionsInTemplate.contains(blockComponent.getPosition())) {
            return; // nothing to do
        }
        positionsInTemplate.remove(blockComponent.getPosition());
        List<Region3i> newTemplateRegions = RegionMergeUtil.mergePositionsIntoRegions(positionsInTemplate);
        editorComponent.absoluteRegionsWithTemplate = newTemplateRegions;
        editorEnitity.saveComponent(editorComponent);
    }

    @ReceiveEvent
    public void onRequestStructurePlaceholderPrefabSelection(RequestStructurePlaceholderPrefabSelection event, EntityRef entity,
                                                             StructurePlaceholderComponent component) {
        component.selectedPrefab = event.getPrefab();
        entity.saveComponent(component);
    }

    @ReceiveEvent
    public void onRequestStructureTemplatePropertiesChange(RequestStructureTemplatePropertiesChange event, EntityRef entity,
                                                           StructureTemplateComponent component) {
        component.type = event.getPrefab();
        component.spawnChance = event.getSpawnChance();
        entity.saveComponent(component);
    }

    @ReceiveEvent
    public void onCreateStructureSpawnItemRequest(CreateStructureSpawnItemRequest event, EntityRef entity,
                            StructureTemplateEditorComponent structureTemplateEditorComponent,
                            BlockComponent blockComponent) {
        EntityBuilder entityBuilder = entityManager.newBuilder("StructureTemplates:structureSpawnItem");
        addComponentsToTemplate(entity, structureTemplateEditorComponent, blockComponent, entityBuilder);
        EntityRef structureSpawnItem = entityBuilder.build();

        EntityRef character = event.getInstigator().getOwner().getComponent(ClientComponent.class).character;
        // TODO check permission once PermissionManager is public API
        inventoryManager.giveItem(character, EntityRef.NULL, structureSpawnItem);
    }

    private void addComponentsToTemplate(EntityRef editorEntity,
                                         StructureTemplateEditorComponent structureTemplateEditorComponent,
                                         BlockComponent blockComponent,
                                         MutableComponentContainer templateEntity) {
        BlockRegionTransform transformToRelative = createAbsoluteToRelativeTransform(blockComponent);

        Map<Block, Set<Vector3i>> blockToAbsPositionsMap = createBlockToAbsolutePositionsMap(
                structureTemplateEditorComponent);


        BuildStructureTemplateEntityEvent createTemplateEvent = new BuildStructureTemplateEntityEvent(templateEntity,
                transformToRelative, blockToAbsPositionsMap);
        editorEntity.send(createTemplateEvent);
    }

    @ReceiveEvent
    public void onBuildStructureTemplate(BuildStructureTemplateEntityEvent event, EntityRef entity,
                                                StructureTemplateComponent componentOfEditor) {
        MutableComponentContainer templateEntity = event.getTemplateEntity();
        templateEntity.addOrSaveComponent(componentLibrary.copy(componentOfEditor));
    }

    @ReceiveEvent
    public void onBuildTemplateWithBlockRegions(BuildStructureTemplateEntityEvent event, EntityRef entity,
                                                StructureTemplateEditorComponent structureTemplateEditorComponent) {
        BlockRegionTransform transformToRelative = event.getTransformToRelative();
        SpawnBlockRegionsComponent spawnBlockRegionsComponent = new SpawnBlockRegionsComponent();
        spawnBlockRegionsComponent.regionsToFill = createRegionsToFill(structureTemplateEditorComponent,
                transformToRelative);
        MutableComponentContainer templateEntity = event.getTemplateEntity();
        templateEntity.addOrSaveComponent(spawnBlockRegionsComponent);
    }

    @ReceiveEvent
    public void onBuildTemplateWithScheduledStructurePlacment(BuildStructureTemplateEntityEvent event, EntityRef entity) {
        BlockRegionTransform transformToRelative = event.getTransformToRelative();
        BlockFamily blockFamily = blockManager.getBlockFamily("StructureTemplates:StructurePlaceholder");

        List<ScheduleStructurePlacementComponent.PlacementToSchedule> placementToSchedules = new ArrayList<>();
        for (Vector3i position: event.findAbsolutePositionsOf(blockFamily)) {
            EntityRef blockEntity = blockEntityRegistry.getBlockEntityAt(position);
            StructurePlaceholderComponent structurePlaceholderComponent = blockEntity.getComponent(
                    StructurePlaceholderComponent.class);
            if (structurePlaceholderComponent.selectedPrefab == null) {
                continue;
            }
            BlockComponent blockComponent = blockEntity.getComponent(BlockComponent.class);
            ScheduleStructurePlacementComponent.PlacementToSchedule placementToSchedule = new ScheduleStructurePlacementComponent.PlacementToSchedule();
            placementToSchedule.position = transformToRelative.transformVector3i(blockComponent.getPosition());
            placementToSchedule.position.subY(1); // placeholder is on top of marked block
            placementToSchedule.front = transformToRelative.transformSide(blockComponent.getBlock().getDirection());
            placementToSchedule.structureTemplateType = structurePlaceholderComponent.selectedPrefab;
            placementToSchedules.add(placementToSchedule);
        }
        if (placementToSchedules.size() > 0) {
            ScheduleStructurePlacementComponent scheduleStructurePlacementComponent = new ScheduleStructurePlacementComponent();
            scheduleStructurePlacementComponent.placementsToSchedule = placementToSchedules;
            event.getTemplateEntity().addOrSaveComponent(scheduleStructurePlacementComponent);
        }
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_CRITICAL)
    public void onBuildTemplateComponentString(BuildStructureTemplateStringEvent event, EntityRef template,
                                                      StructureTemplateComponent component) {
        StringBuilder sb = new StringBuilder();
        sb.append("    \"StructureTemplate\": {\n");
        boolean firstProperty = true;
        if (component.type != null) {
            sb.append("        \"type\": \"");
            sb.append(component.type.getUrn().toString());
            sb.append("\"");
            firstProperty = false;
        }
        StructureTemplateComponent defaultComponent = new StructureTemplateComponent();
        if (component.spawnChance != defaultComponent.spawnChance) {
            if (!firstProperty) {
                sb.append(",\n");
            }
            sb.append("        \"spawnChance\": \"");
            sb.append(Integer.toString(component.spawnChance));
            sb.append("\"");
            firstProperty = false;
        }
        if (!firstProperty) {
            sb.append("\n");
        }
        sb.append("    }");
        event.addJsonForComponent(sb.toString(), StructureTemplateComponent.class);
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_HIGH)
    public void onBuildTemplateStringWithBlockRegions(BuildStructureTemplateStringEvent event, EntityRef template,
                                               SpawnBlockRegionsComponent component) {
        StringBuilder sb = new StringBuilder();
        sb.append("    \"SpawnBlockRegions\": {\n");
        sb.append("        \"regionsToFill\": [\n");
        sb.append(formatAsString(component.regionsToFill));
        sb.append("        ]\n");
        sb.append("    }");
        event.addJsonForComponent(sb.toString(), SpawnBlockRegionsComponent.class);
    }

    @ReceiveEvent
    public void onBuildTemplateStringWithBlockRegions(BuildStructureTemplateStringEvent event, EntityRef template,
                                                      ScheduleStructurePlacementComponent component) {
        StringBuilder sb = new StringBuilder();
        sb.append("    \"ScheduleStructurePlacement\": {\n");
        sb.append("        \"placementsToSchedule\": [\n");
        ListUtil.visitList(component.placementsToSchedule,
                (ScheduleStructurePlacementComponent.PlacementToSchedule placementToSchedule, boolean last)-> {
            sb.append("            {\n");
            sb.append("                \"structureTemplateType\": \"");
            sb.append(placementToSchedule.structureTemplateType.getUrn().toString());
            sb.append("\",\n");
            sb.append("                \"front\": \"");
            sb.append(placementToSchedule.front.name());
            sb.append("\",\n");
            sb.append("                \"position\": [");
            sb.append(placementToSchedule.position.x);
            sb.append(", ");
            sb.append(placementToSchedule.position.y);
            sb.append(", ");
            sb.append(placementToSchedule.position.z);
            sb.append("]\n");
            if (last) {
                sb.append("        }\n");
            } else {
                sb.append("        },\n");
            }
        });
        sb.append("        ]\n");
        sb.append("    }");
        event.addJsonForComponent(sb.toString(), ScheduleStructurePlacementComponent.class);

    }

    @ReceiveEvent
    public void onCopyBlockRegionRequest(StructureTemplateStringRequest event, EntityRef entity,
                                         StructureTemplateEditorComponent structureTemplateEditorComponent,
                                         BlockComponent blockComponent) {
        EntityBuilder entityBuilder = entityManager.newBuilder();
        addComponentsToTemplate(entity, structureTemplateEditorComponent, blockComponent, entityBuilder);
        EntityRef templateEntity = entityBuilder.build();
        StringBuilder sb = new StringBuilder();
        BuildStructureTemplateStringEvent buildStringEvent = new BuildStructureTemplateStringEvent();
        templateEntity.send(buildStringEvent);
        String textToSend = buildStringEvent.getMap().values().stream().collect(Collectors.joining(",\n", "{\n", "\n}\n"));
        templateEntity.destroy();

        CopyBlockRegionResultEvent resultEvent = new CopyBlockRegionResultEvent(textToSend);
        event.getInstigator().send(resultEvent);
    }


    @ReceiveEvent
    public void onMakeBoxShapedRequest(MakeBoxShapedRequest event, EntityRef entity,
                                       StructureTemplateEditorComponent structureTemplateEditorComponent) {
        structureTemplateEditorComponent.absoluteRegionsWithTemplate = new ArrayList<>(Arrays.asList(event.getRegion()));
        entity.saveComponent(structureTemplateEditorComponent);
    }


    @ReceiveEvent(components = {BlockItemComponent.class})
    public void onBlockItemPlaced(OnBlockItemPlaced event, EntityRef itemEntity,
                         StructureTemplateEditorComponent editorComponentOfItem) {
        EntityRef placedBlockEntity = event.getPlacedBlock();

        StructureTemplateEditorComponent editorComponentOfBlock = placedBlockEntity.getComponent(StructureTemplateEditorComponent.class );
        if (editorComponentOfBlock == null) {
            editorComponentOfBlock = new StructureTemplateEditorComponent();
        }
        editorComponentOfBlock.absoluteRegionsWithTemplate = new ArrayList<>(editorComponentOfItem.absoluteRegionsWithTemplate);
        placedBlockEntity.saveComponent(editorComponentOfBlock);

        StructureTemplateComponent structureTemplateComponentOfItem = itemEntity.getComponent(StructureTemplateComponent.class);
        if (structureTemplateComponentOfItem == null) {
            structureTemplateComponentOfItem = new StructureTemplateComponent();
        }
        placedBlockEntity.addOrSaveComponent(componentLibrary.copy(structureTemplateComponentOfItem));
    }

    @ReceiveEvent(components = {})
    public void onBlockToItem(OnBlockToItem event, EntityRef blockEntity,
                                   StructureTemplateEditorComponent componentOfBlock) {
        EntityRef item = event.getItem();
        StructureTemplateEditorComponent componentOfItem = item.getComponent(StructureTemplateEditorComponent.class);
        if (componentOfItem == null) {
            componentOfItem = new StructureTemplateEditorComponent();
        }
        componentOfItem.absoluteRegionsWithTemplate = new ArrayList<>(componentOfBlock.absoluteRegionsWithTemplate);
        item.saveComponent(componentOfItem);

        StructureTemplateComponent structureTemplateComponentOfBlock = blockEntity.getComponent(StructureTemplateComponent.class);
        if (structureTemplateComponentOfBlock == null) {
            structureTemplateComponentOfBlock = new StructureTemplateComponent();
        }
        item.addOrSaveComponent(componentLibrary.copy(structureTemplateComponentOfBlock));
    }

    private Map<Block,Set<Vector3i>> createBlockToAbsolutePositionsMap(
            StructureTemplateEditorComponent structureTemplateEditorComponent) {
        List<Region3i> absoluteRegions = structureTemplateEditorComponent.absoluteRegionsWithTemplate;

        Map<Block, Set<Vector3i>> map = new HashMap<>();
        for (Region3i absoluteRegion : absoluteRegions) {
            for (Vector3i absolutePosition : absoluteRegion) {
                Block block = worldProvider.getBlock(absolutePosition);
                Set<Vector3i> positions = map.get(block);
                if (positions == null) {
                    positions = new HashSet<>();
                    map.put(block, positions);
                }
                positions.add(new Vector3i(absolutePosition));
            }
        }
        return map;
    }


    private List<RegionToFill> createRegionsToFill(StructureTemplateEditorComponent structureTemplateEditorComponent,
                                                   BlockRegionTransform transformToRelative) {
        List<Region3i> absoluteRegions = structureTemplateEditorComponent.absoluteRegionsWithTemplate;

        List<RegionToFill> regionsToFill = new ArrayList<>();
        for (Region3i absoluteRegion: absoluteRegions) {
            for (Vector3i absolutePosition : absoluteRegion) {
                EntityRef blockEntity = blockEntityRegistry.getBlockEntityAt(absolutePosition);
                BlockPlaceholderComponent placeholderComponent = blockEntity.getComponent(BlockPlaceholderComponent.class);
                Block block;
                if (placeholderComponent != null) {
                    block = placeholderComponent.block;
                } else {
                    block = worldProvider.getBlock(absolutePosition);
                }
                if (block == null) {
                    continue;
                }
                Block relativeBlock = transformToRelative.transformBlock(block);
                RegionToFill regionToFill = new RegionToFill();
                Vector3i relativePosition = transformToRelative.transformVector3i(absolutePosition);
                Region3i region = Region3i.createBounded(relativePosition, relativePosition);
                regionToFill.region = region;
                regionToFill.blockType = relativeBlock;
                regionsToFill.add(regionToFill);


            }
        }
        RegionMergeUtil.mergeRegionsToFill(regionsToFill);
        regionsToFill.sort(REGION_BY_BLOCK_TYPE_COMPARATOR.thenComparing(REGION_BY_MIN_Z_COMPARATOR)
                .thenComparing(REGION_BY_MIN_X_COMPARATOR).thenComparing(REGION_BY_MIN_Y_COMPARATOR));
        return regionsToFill;
    }



    // TODO move 2 methods to utility class
    public static BlockRegionTransform createAbsoluteToRelativeTransform(BlockComponent blockComponent) {
        Side front = blockComponent.getBlock().getDirection();
        BlockRegionTransformationList transformList = new BlockRegionTransformationList();
        Vector3i minusOrigin = new Vector3i(0, 0, 0);
        minusOrigin.sub(blockComponent.getPosition());
        transformList.addTransformation(new BlockRegionMovement(minusOrigin));
        transformList.addTransformation(
                HorizontalBlockRegionRotation.createRotationFromSideToSide(front, Side.FRONT));
        return transformList;
    }
    public static BlockRegionTransform createRelativeToAbsoluteTransform(BlockComponent blockComponent) {
        Side front = blockComponent.getBlock().getDirection();
        BlockRegionTransformationList transformList = new BlockRegionTransformationList();
        transformList.addTransformation(
                HorizontalBlockRegionRotation.createRotationFromSideToSide(Side.FRONT, front));
        transformList.addTransformation(new BlockRegionMovement(new Vector3i(blockComponent.getPosition())));
        return transformList;
    }



    static String formatAsString(List<RegionToFill> regionsToFill) {
        StringBuilder sb = new StringBuilder();
        ListUtil.visitList(regionsToFill, (RegionToFill regionToFill, boolean last) -> {
            sb.append("            { \"blockType\": \"");
            sb.append(regionToFill.blockType);
            sb.append("\", \"region\": { \"min\": [");
            sb.append(regionToFill.region.minX());
            sb.append(", ");
            sb.append(regionToFill.region.minY());
            sb.append(", ");
            sb.append(regionToFill.region.minZ());
            sb.append("], \"size\": [");
            sb.append(regionToFill.region.sizeX());
            sb.append(", ");
            sb.append(regionToFill.region.sizeY());
            sb.append(", ");
            sb.append(regionToFill.region.sizeZ());
            if (last) {
                sb.append("]}}\n");
            } else {
                sb.append("]}},\n");
            }
        });
        return sb.toString();
    }


    @ReceiveEvent
    public void onActivate(ActivateEvent event, EntityRef entity,
                           SpawnTemplateActionComponent spawnActionComponent) {
        EntityRef target = event.getTarget();
        BlockComponent blockComponent = target.getComponent(BlockComponent.class);
        if (blockComponent == null) {
            return;
        }

        Vector3i position = blockComponent.getPosition();
        Vector3f directionVector = event.getDirection();
        Side directionStructureIsIn = Side.inHorizontalDirection(directionVector.getX(), directionVector.getZ());
        Side frontDirectionOfStructure = directionStructureIsIn.reverse();


        List<Region3i> absoluteRegions = getAbsolutePlacementRegionsOfTemplate(entity, position, frontDirectionOfStructure);


        BlockRegionTransform blockRegionTransform = StructureSpawnServerSystem.getBlockRegionTransformForStructurePlacement(
                event, blockComponent);
        entity.send(new SpawnTemplateEvent(blockRegionTransform));

        StructureTemplateComponent structureTemplateComponentOfItem = entity.getComponent(StructureTemplateComponent.class);
        StructureTemplateComponent newStructureTemplateComponent = componentLibrary.copy(structureTemplateComponentOfItem);
        placeOriginMarkerWithTemplateData(event, position, frontDirectionOfStructure, absoluteRegions,
                newStructureTemplateComponent);
        // TODO check if consuming event and making item consumable works too e.g. event.consume();
        entity.destroy();
    }

    @ReceiveEvent(priority = EventPriority.PRIORITY_NORMAL)
    public void onSpawnTemplateEventWithPlaceholderPriority(SpawnTemplateEvent event, EntityRef entity,
                                                            ScheduleStructurePlacementComponent placementComponent) {
        BlockRegionTransform transformation = event.getTransformation();
        for (ScheduleStructurePlacementComponent.PlacementToSchedule placementToSchedule : placementComponent.placementsToSchedule) {
            Vector3i actualPosition = transformation.transformVector3i(placementToSchedule.position);
            Side side = transformation.transformSide(placementToSchedule.front);
            Prefab selectedTemplateType = placementToSchedule.structureTemplateType;

            BlockFamily blockFamily = blockManager.getBlockFamily("StructureTemplates:StructurePlaceholder");
            HorizontalBlockFamily horizontalBlockFamily = (HorizontalBlockFamily) blockFamily;
            Block block = horizontalBlockFamily.getBlockForSide(side);
            Vector3i positionAbove = new Vector3i(actualPosition);
            positionAbove.addY(1);
            worldProvider.setBlock(positionAbove, block);
            EntityRef blockEntity = blockEntityRegistry.getBlockEntityAt(positionAbove);
            StructurePlaceholderComponent structurePlaceholderComponent = blockEntity.getComponent(StructurePlaceholderComponent.class);
            structurePlaceholderComponent.selectedPrefab = selectedTemplateType;
            blockEntity.saveComponent(structurePlaceholderComponent);
        }

    }


    List<Region3i> getAbsolutePlacementRegionsOfTemplate(EntityRef entity, Vector3i position, Side frontDirectionOfStructure) {
        List<Region3i> relativeRegions = getPlacementRegionsOfTemplate(entity);

        // TODO reuse createRelativeToAbsoluteTransform
        HorizontalBlockRegionRotation rotation = HorizontalBlockRegionRotation.createRotationFromSideToSide(Side.FRONT,
                frontDirectionOfStructure);
        List<Region3i> absoluteRegions = new ArrayList<>();
        for (Region3i relativeRegion: relativeRegions) {
            Region3i absoluteRegion = rotation.transformRegion(relativeRegion);
            absoluteRegion = absoluteRegion.move(position);
            absoluteRegions.add(absoluteRegion);
        }

        Set<Vector3i> positionsInTemplate = RegionMergeUtil.positionsOfRegions(absoluteRegions);
        List<Region3i> newTemplateRegions = RegionMergeUtil.mergePositionsIntoRegions(positionsInTemplate);
        return newTemplateRegions;
    }

    private List<Region3i> getPlacementRegionsOfTemplate(EntityRef entity) {
        List<Region3i> relativeRegions = Lists.newArrayList();
        SpawnBlockRegionsComponent blockRegionsComponent = entity.getComponent(SpawnBlockRegionsComponent.class);
        if (blockRegionsComponent != null) {
            for (RegionToFill regionToFill : blockRegionsComponent.regionsToFill) {
                relativeRegions.add(regionToFill.region);
            }
        }
        return relativeRegions;
    }

    private Region3i getPlacementBoundingsOfTemplate(EntityRef entity) {
        Region3i unrotatedRegion = null;
        SpawnBlockRegionsComponent blockRegionsComponent = entity.getComponent(SpawnBlockRegionsComponent.class);
        if (blockRegionsComponent != null) {
            for (RegionToFill regionToFill : blockRegionsComponent.regionsToFill) {
                if (unrotatedRegion == null) {
                    unrotatedRegion = regionToFill.region;
                } else {
                    unrotatedRegion = unrotatedRegion.expandToContain(regionToFill.region.min());
                    unrotatedRegion = unrotatedRegion.expandToContain(regionToFill.region.max());
                }
            }
        }
        if (unrotatedRegion == null) {
            unrotatedRegion = Region3i.createBounded(new Vector3i(0, 0, 0), new Vector3i(0, 0, 0));
        }
        return unrotatedRegion;
    }

    boolean placeOriginMarkerWithTemplateData(ActivateEvent event, Vector3i position, Side frontDirectionOfStructure,
                                              List<Region3i> regions,
                                              StructureTemplateComponent newStructureTemplateComponent) {
        boolean originPlaced = placeOriginMarkerBlockWithoutData(event, position, frontDirectionOfStructure);
        if (!originPlaced) {
            LOGGER.info("Structure template origin placement got denied");
            return false;
        }
        EntityRef originBlockEntity = blockEntityRegistry.getBlockEntityAt(position);
        addTemplateDataToBlockEntity(position, regions, originBlockEntity, newStructureTemplateComponent);
        return true;
    }

    private void addTemplateDataToBlockEntity(Vector3i position, List<Region3i> regions, EntityRef originBlockEntity,
                                              StructureTemplateComponent newStructureTemplateComponent) {
        StructureTemplateEditorComponent editorComponent = originBlockEntity.getComponent(StructureTemplateEditorComponent.class);
        editorComponent.absoluteRegionsWithTemplate = new ArrayList<>(regions);
        originBlockEntity.saveComponent(editorComponent);
        originBlockEntity.addOrSaveComponent(newStructureTemplateComponent);
    }

    private boolean placeOriginMarkerBlockWithoutData(ActivateEvent event, Vector3i position, Side frontDirectionOfStructure) {
        BlockFamily blockFamily = blockManager.getBlockFamily("StructureTemplates:StructureTemplateEditor");
        HorizontalBlockFamily horizontalBlockFamily = (HorizontalBlockFamily) blockFamily;
        Block block = horizontalBlockFamily.getBlockForSide(frontDirectionOfStructure);

        PlaceBlocks placeBlocks = new PlaceBlocks(position, block, event.getInstigator());
        worldProvider.getWorldEntity().send(placeBlocks);
        return !placeBlocks.isConsumed();
    }

}
