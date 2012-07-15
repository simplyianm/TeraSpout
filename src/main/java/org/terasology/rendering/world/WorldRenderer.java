/*
 * Copyright 2012 Benjamin Glatzel <benjamin.glatzel@me.com>
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
package org.terasology.rendering.world;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_LIGHT0;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.glBlendFunc;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glColorMask;
import static org.lwjgl.opengl.GL11.glCullFace;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL11.glLoadIdentity;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL11.glPopMatrix;
import static org.lwjgl.opengl.GL11.glPushMatrix;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.terasology.componentSystem.RenderSystem;
import org.terasology.componentSystem.controllers.LocalPlayerSystem;
import org.terasology.components.AABBCollisionComponent;
import org.terasology.components.PlayerComponent;
import org.terasology.entitySystem.EntityManager;
import org.terasology.game.ComponentSystemManager;
import org.terasology.game.CoreRegistry;
import org.terasology.game.TerasologyEngine;
import org.terasology.game.Timer;
import org.terasology.logic.LocalPlayer;
import org.terasology.logic.generators.DefaultGenerators;
import org.terasology.logic.manager.AudioManager;
import org.terasology.logic.manager.Config;
import org.terasology.logic.manager.PathManager;
import org.terasology.logic.manager.PortalManager;
import org.terasology.logic.manager.PostProcessingRenderer;
import org.terasology.logic.manager.ShaderManager;
import org.terasology.logic.manager.WorldTimeEventManager;
import org.terasology.logic.world.BlockEntityRegistry;
import org.terasology.logic.world.Chunk;
import org.terasology.logic.world.ChunkProvider;
import org.terasology.logic.world.ChunkStore;
import org.terasology.logic.world.EntityAwareWorldProvider;
import org.terasology.logic.world.LocalChunkProvider;
import org.terasology.logic.world.WorldBiomeProvider;
import org.terasology.logic.world.WorldBiomeProviderImpl;
import org.terasology.logic.world.WorldInfo;
import org.terasology.logic.world.WorldProvider;
import org.terasology.logic.world.WorldProviderCoreImpl;
import org.terasology.logic.world.WorldProviderWrapper;
import org.terasology.logic.world.WorldTimeEvent;
import org.terasology.logic.world.WorldUtil;
import org.terasology.logic.world.WorldView;
import org.terasology.logic.world.chunkStore.ChunkStoreGZip;
import org.terasology.logic.world.generator.core.ChunkGeneratorManager;
import org.terasology.logic.world.generator.core.ChunkGeneratorManagerImpl;
import org.terasology.logic.world.generator.core.FloraGenerator;
import org.terasology.logic.world.generator.core.ForestGenerator;
import org.terasology.logic.world.generator.core.LiquidsGenerator;
import org.terasology.logic.world.generator.core.PerlinTerrainGenerator;
import org.terasology.math.Rect2i;
import org.terasology.math.Region3i;
import org.terasology.math.TeraMath;
import org.terasology.math.Vector3i;
import org.terasology.model.blocks.Block;
import org.terasology.model.blocks.management.BlockManager;
import org.terasology.model.structures.AABB;
import org.terasology.model.structures.BlockPosition;
import org.terasology.performanceMonitor.PerformanceMonitor;
import org.terasology.rendering.cameras.Camera;
import org.terasology.rendering.cameras.DefaultCamera;
import org.terasology.rendering.interfaces.IGameObject;
import org.terasology.rendering.physics.BulletPhysicsRenderer;
import org.terasology.rendering.primitives.ChunkMesh;
import org.terasology.rendering.primitives.ChunkTessellator;
import org.terasology.rendering.shader.ShaderProgram;

import com.google.common.collect.Lists;

/**
 * The world of Terasology. At its most basic the world contains chunks (consisting of a fixed amount of blocks)
 * and the player.
 * <p/>
 * The world is randomly generated by using a bunch of Perlin noise generators initialized
 * with a favored seed value.
 *
 * @author Benjamin Glatzel <benjamin.glatzel@me.com>
 */
public final class WorldRenderer implements IGameObject {
    public static final int MAX_ANIMATED_CHUNKS = 64;
    public static final int MAX_BILLBOARD_CHUNKS = 64;
    public static final int VERTICAL_SEGMENTS = Config.getInstance().getVerticalChunkMeshSegments();

    /* WORLD PROVIDER */
    private final WorldProvider _worldProvider;
    private ChunkProvider _chunkProvider;
    private ChunkStore chunkStore;
    private Logger _logger = Logger.getLogger(getClass().getName());

    /* PLAYER */
    private LocalPlayer _player;

    /* CAMERA */
    public enum CAMERA_MODE {
        PLAYER,
        SPAWN
    }

    private CAMERA_MODE _cameraMode = CAMERA_MODE.PLAYER;
    private Camera _spawnCamera = new DefaultCamera();
    private DefaultCamera _defaultCamera = new DefaultCamera();
    private Camera _activeCamera = _defaultCamera;

    /* CHUNKS */
    private ChunkTessellator _chunkTesselator;
    private boolean _pendingChunks = false;
    private final List<Chunk> _chunksInProximity = Lists.newArrayList();
    private int _chunkPosX, _chunkPosZ;

    /* RENDERING */
    private final LinkedList<IGameObject> _renderQueueTransparent = Lists.newLinkedList();
    private final LinkedList<Chunk> _renderQueueChunksOpaque = Lists.newLinkedList();
    private final PriorityQueue<Chunk> _renderQueueChunksSortedWater = new PriorityQueue<Chunk>(16 * 16, new ChunkProximityComparator());
    private final PriorityQueue<Chunk> _renderQueueChunksSortedBillboards = new PriorityQueue<Chunk>(16 * 16, new ChunkProximityComparator());

    /* CORE GAME OBJECTS */
    private final PortalManager _portalManager;

    /* HORIZON */
    private final Skysphere _skysphere;

    /* TICKING */
    private Timer _timer = CoreRegistry.get(Timer.class);
    private float _tick = 0;
    private int _tickTock = 0;
    private long _lastTick;

    /* UPDATING */
    private final ChunkUpdateManager _chunkUpdateManager;

    /* EVENTS */
    private final WorldTimeEventManager _worldTimeEventManager;

    /* PHYSICS */
    private final BulletPhysicsRenderer _bulletRenderer;

    /* BLOCK GRID */
    private final BlockGrid _blockGrid;

    /* STATISTICS */
    private int _statDirtyChunks = 0, _statVisibleChunks = 0, _statIgnoredPhases = 0;
    private int _statChunkMeshEmpty, _statChunkNotReady, _statRenderedTriangles;

    /* OTHER SETTINGS */
    private boolean _wireframe;

    private ComponentSystemManager _systemManager;

    /**
     * Initializes a new (local) world for the single player mode.
     *
     * @param title The title/description of the world
     * @param seed  The seed string used to generate the terrain
     */
    public WorldRenderer(String title, String seed, long time, EntityManager manager, LocalPlayerSystem localPlayerSystem) {
        // TODO: Cleaner method for this?
        File f = new File(PathManager.getInstance().getWorldSavePath(title), title + ".dat");
        if (f.exists()) {
            try {
                chunkStore = ChunkStoreGZip.load(f);
            } catch (IOException e) {
                /* TODO: We really should expose this error via UI so player knows that there is an issue with their world
                   (don't have the game continue or we risk overwriting their game)
                 */
                e.printStackTrace();
            }
        }
        if (chunkStore == null) {
            chunkStore = new ChunkStoreGZip();
        }
        _chunkProvider = new LocalChunkProvider(chunkStore, generatorManager); // FIXME teraspout - change this to only be a renderer
        EntityAwareWorldProvider entityWorldProvider = new EntityAwareWorldProvider(new WorldProviderCoreImpl(title, seed, time, _chunkProvider));
        CoreRegistry.put(BlockEntityRegistry.class, entityWorldProvider);
        CoreRegistry.get(ComponentSystemManager.class).register(entityWorldProvider, "engine:BlockEntityRegistry");
        _worldProvider = new WorldProviderWrapper(entityWorldProvider);
        _chunkTesselator = new ChunkTessellator(_worldProvider.getBiomeProvider());
        _skysphere = new Skysphere(this);
        _chunkUpdateManager = new ChunkUpdateManager(_chunkTesselator, _worldProvider);
        _worldTimeEventManager = new WorldTimeEventManager(_worldProvider);
        _portalManager = new PortalManager(manager);
        _blockGrid = new BlockGrid();
        _bulletRenderer = new BulletPhysicsRenderer(this);

        // TODO: won't need localPlayerSystem here once camera is in the ES proper
        localPlayerSystem.setPlayerCamera(_defaultCamera);
        _systemManager = CoreRegistry.get(ComponentSystemManager.class);


        initTimeEvents();
    }

    /**
     * Updates the list of chunks around the player.
     *
     * @param force Forces the update
     * @return True if the list was changed
     */
    public boolean updateChunksInProximity(boolean force) {
        int newChunkPosX = calcCamChunkOffsetX();
        int newChunkPosZ = calcCamChunkOffsetZ();

        // TODO: This should actually be done based on events from the ChunkProvider on new chunk availability/old chunk removal
        int viewingDistance = Config.getInstance().getActiveViewingDistance();

        if (_chunkPosX != newChunkPosX || _chunkPosZ != newChunkPosZ || force || _pendingChunks) {
            // just add all visible chunks
            if (_chunksInProximity.size() == 0 || force || _pendingChunks) {
                _chunksInProximity.clear();
                for (int x = -(viewingDistance / 2); x < viewingDistance / 2; x++) {
                    for (int z = -(viewingDistance / 2); z < viewingDistance / 2; z++) {
                        Chunk c = _chunkProvider.getChunk(newChunkPosX + x, 0, newChunkPosZ + z);
                        if (c != null && c.getChunkState() == Chunk.State.COMPLETE && _worldProvider.getWorldViewAround(c.getPos()) != null) {
                            _chunksInProximity.add(c);
                        } else {
                            _pendingChunks = true;
                        }
                    }
                }
            }
            // adjust proximity chunk list
            else {
                int vd2 = viewingDistance / 2;

                Rect2i oldView = new Rect2i(_chunkPosX - vd2, _chunkPosZ - vd2, viewingDistance, viewingDistance);
                Rect2i newView = new Rect2i(newChunkPosX - vd2, newChunkPosZ - vd2, viewingDistance, viewingDistance);

                // remove
                List<Rect2i> removeRects = Rect2i.subtractEqualsSized(oldView, newView);
                for (Rect2i r : removeRects) {
                    for (int x = r.minX(); x < r.maxX(); ++x) {
                        for (int y = r.minY(); y < r.maxY(); ++y) {
                            Chunk c = _chunkProvider.getChunk(x, 0, y);
                            _chunksInProximity.remove(c);
                        }
                    }
                }

                // add
                List<Rect2i> addRects = Rect2i.subtractEqualsSized(newView, oldView);
                for (Rect2i r : addRects) {
                    for (int x = r.minX(); x < r.maxX(); ++x) {
                        for (int y = r.minY(); y < r.maxY(); ++y) {
                            Chunk c = _chunkProvider.getChunk(x, 0, y);
                            if (c != null && c.getChunkState() == Chunk.State.COMPLETE && _worldProvider.getWorldViewAround(c.getPos()) != null) {
                                _chunksInProximity.add(c);
                            } else {
                                _pendingChunks = true;
                            }
                        }
                    }
                }
            }

            _chunkPosX = newChunkPosX;
            _chunkPosZ = newChunkPosZ;


            Collections.sort(_chunksInProximity, new ChunkProximityComparator());

            return true;
        }

        return false;
    }

    private static class ChunkProximityComparator implements Comparator<Chunk> {

        @Override
        public int compare(Chunk o1, Chunk o2) {
            double distance = distanceToCamera(o1);
            double distance2 = distanceToCamera(o2);

            if (o1 == null) {
                return -1;
            } else if (o2 == null) {
                return 1;
            }

            if (distance == distance2)
                return 0;

            return distance2 > distance ? -1 : 1;
        }

        private float distanceToCamera(Chunk chunk) {
            Vector3f result = new Vector3f((chunk.getPos().x + 0.5f) * Chunk.SIZE_X, 0, (chunk.getPos().z + 0.5f) * Chunk.SIZE_Z);

            Vector3d cameraPos = CoreRegistry.get(WorldRenderer.class).getActiveCamera().getPosition();
            result.x -= cameraPos.x;
            result.z -= cameraPos.z;

            return result.length();
        }
    }

    private Vector3f getPlayerPosition() {
        if (_player != null) {
            return _player.getPosition();
        }
        return new Vector3f();
    }

    /**
     * Creates the world time events to play the game's soundtrack at specific times.
     */
    public void initTimeEvents() {
        // SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.1, true) {
            @Override
            public void run() {
                AudioManager.playMusic("engine:Sunrise");
            }
        });

        // AFTERNOON
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.25, true) {
            @Override
            public void run() {
                AudioManager.playMusic("engine:Afternoon");
            }
        });

        // SUNSET
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.4, true) {
            @Override
            public void run() {
                AudioManager.playMusic("engine:Sunset");
            }
        });

        // NIGHT
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.6, true) {
            @Override
            public void run() {
                AudioManager.playMusic("engine:Dimlight");
            }
        });

        // NIGHT
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.75, true) {
            @Override
            public void run() {
                AudioManager.playMusic("engine:OtherSide");
            }
        });

        // BEFORE SUNRISE
        _worldTimeEventManager.addWorldTimeEvent(new WorldTimeEvent(0.9, true) {
            @Override
            public void run() {
                AudioManager.playMusic("engine:Resurface");
            }
        });
    }

    /**
     * Updates the currently visible chunks (in sight of the player).
     */
    public void updateAndQueueVisibleChunks() {
        _statDirtyChunks = 0;
        _statVisibleChunks = 0;
        _statIgnoredPhases = 0;

        for (int i = 0; i < _chunksInProximity.size(); i++) {
            Chunk c = _chunksInProximity.get(i);
            ChunkMesh[] mesh = c.getMesh();

            if (isChunkVisible(c) && isChunkValidForRender(c)) {

                if (triangleCount(mesh, ChunkMesh.RENDER_PHASE.OPAQUE) > 0)
                    _renderQueueChunksOpaque.add(c);
                else
                    _statIgnoredPhases++;

                if (triangleCount(mesh, ChunkMesh.RENDER_PHASE.WATER_AND_ICE) > 0)
                    _renderQueueChunksSortedWater.add(c);
                else
                    _statIgnoredPhases++;

                if (triangleCount(mesh, ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT) > 0 && i < MAX_BILLBOARD_CHUNKS)
                    _renderQueueChunksSortedBillboards.add(c);
                else
                    _statIgnoredPhases++;

                if (i < MAX_ANIMATED_CHUNKS)
                    c.setAnimated(true);
                else
                    c.setAnimated(false);

                if (c.getPendingMesh() != null) {
                    for (int j = 0; j < c.getPendingMesh().length; j++) {
                        c.getPendingMesh()[j].generateVBOs();
                    }
                    if (c.getMesh() != null) {
                        for (int j = 0; j < c.getMesh().length; j++) {
                            c.getMesh()[j].dispose();
                        }
                    }
                    c.setMesh(c.getPendingMesh());
                    c.setPendingMesh(null);
                }

                if ((c.isDirty() || mesh == null) && isChunkValidForRender(c)) {
                    _statDirtyChunks++;
                    _chunkUpdateManager.queueChunkUpdate(c, ChunkUpdateManager.UPDATE_TYPE.DEFAULT);
                }

                _statVisibleChunks++;
            } else if (i > Config.getInstance().getMaxChunkVBOs()) {
                if (mesh != null) {
                    // Make sure not too many chunk VBOs are available in the video memory at the same time
                    // Otherwise VBOs are moved into system memory which is REALLY slow and causes lag
                    for (ChunkMesh m : mesh) {
                        m.dispose();
                    }
                    c.setMesh(null);
                }
            }
        }
    }

    private int triangleCount(ChunkMesh[] mesh, ChunkMesh.RENDER_PHASE type) {
        int count = 0;

        if (mesh != null)
            for (int i = 0; i < mesh.length; i++)
                count += mesh[i].triangleCount(type);

        return count;
    }

    private void resetStats() {
        _statChunkMeshEmpty = 0;
        _statChunkNotReady = 0;
        _statRenderedTriangles = 0;
    }

    /**
     * Renders the world.
     */
    @Override
    public void render() {
        _renderQueueTransparent.add(_bulletRenderer);
        resetStats();

        updateAndQueueVisibleChunks();

        if (Config.getInstance().isComplexWater()) {
            PostProcessingRenderer.getInstance().beginRenderReflectedScene();
            glCullFace(GL11.GL_FRONT);
            getActiveCamera().setReflected(true);
            renderWorldReflection(getActiveCamera());
            getActiveCamera().setReflected(false);
            glCullFace(GL11.GL_BACK);
            PostProcessingRenderer.getInstance().endRenderReflectedScene();
        }

        PostProcessingRenderer.getInstance().beginRenderScene();
        renderWorld(getActiveCamera());
        PostProcessingRenderer.getInstance().endRenderScene();

        /* RENDER THE FINAL POST-PROCESSED SCENE */
        PerformanceMonitor.startActivity("Render Post-Processing");
        PostProcessingRenderer.getInstance().renderScene();
        PerformanceMonitor.endActivity();

        if (_cameraMode == CAMERA_MODE.PLAYER) {
            glClear(GL_DEPTH_BUFFER_BIT);
            glPushMatrix();
            glLoadIdentity();
            _activeCamera.loadProjectionMatrix(80f);

            PerformanceMonitor.startActivity("Render First Person");
            for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
                renderer.renderFirstPerson();
            }
            PerformanceMonitor.endActivity();

            glPopMatrix();
        }
    }

    public void renderWorld(Camera camera) {
        /* SKYSPHERE */
        PerformanceMonitor.startActivity("Render Sky");
        camera.lookThroughNormalized();
        _skysphere.render();
        PerformanceMonitor.endActivity();

        /* WORLD RENDERING */
        PerformanceMonitor.startActivity("Render World");
        camera.lookThrough();
        if (Config.getInstance().isDebugCollision()) {
            renderDebugCollision(camera);
        }

        glEnable(GL_LIGHT0);

        boolean headUnderWater;

        headUnderWater = _cameraMode == CAMERA_MODE.PLAYER && isUnderwater();

        if (_wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_LINE);

        PerformanceMonitor.startActivity("RenderOpaque");

        for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
            renderer.renderOpaque();
        }


        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkOpaque");

        /*
         * FIRST RENDER PASS: OPAQUE ELEMENTS
         */
        while (_renderQueueChunksOpaque.size() > 0)
            renderChunk(_renderQueueChunksOpaque.poll(), ChunkMesh.RENDER_PHASE.OPAQUE, camera);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkTransparent");

        /*
         * SECOND RENDER PASS: BILLBOARDS
         */
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        while (_renderQueueChunksSortedBillboards.size() > 0)
            renderChunk(_renderQueueChunksSortedBillboards.poll(), ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT, camera);

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render Transparent");

        while (_renderQueueTransparent.size() > 0)
            _renderQueueTransparent.poll().render();
        for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
            renderer.renderTransparent();
        }

        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Render ChunkWaterIce");

        // Make sure the water surface is rendered if the player is swimming
        if (headUnderWater) {
            glDisable(GL11.GL_CULL_FACE);
        }

        /*
        * THIRD (AND FOURTH) RENDER PASS: WATER AND ICE
        */
        while (_renderQueueChunksSortedWater.size() > 0) {
            Chunk c = _renderQueueChunksSortedWater.poll();

            for (int j = 0; j < 2; j++) {

                if (j == 0) {
                    glColorMask(false, false, false, false);
                    renderChunk(c, ChunkMesh.RENDER_PHASE.WATER_AND_ICE, camera);
                } else {
                    glColorMask(true, true, true, true);
                    renderChunk(c, ChunkMesh.RENDER_PHASE.WATER_AND_ICE, camera);
                }
            }
        }

        for (RenderSystem renderer : _systemManager.iterateRenderSubscribers()) {
            renderer.renderOverlay();
        }

        glDisable(GL_BLEND);

        if (headUnderWater)
            glEnable(GL11.GL_CULL_FACE);

        if (_wireframe)
            glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        glDisable(GL_LIGHT0);

        PerformanceMonitor.endActivity();
    }

    public void renderWorldReflection(Camera camera) {
        PerformanceMonitor.startActivity("Render Sky");
        camera.lookThroughNormalized();
        _skysphere.render();

        camera.lookThrough();

        glEnable(GL_LIGHT0);

        for (Chunk c : _renderQueueChunksOpaque)
            renderChunk(c, ChunkMesh.RENDER_PHASE.OPAQUE, camera);

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        for (Chunk c : _renderQueueChunksSortedBillboards)
            renderChunk(c, ChunkMesh.RENDER_PHASE.BILLBOARD_AND_TRANSLUCENT, camera);

        for (IGameObject g : _renderQueueTransparent)
            g.render();

        glDisable(GL_BLEND);
        glDisable(GL_LIGHT0);
    }

    private void renderChunk(Chunk chunk, ChunkMesh.RENDER_PHASE phase, Camera camera) {
        if (chunk.getChunkState() == Chunk.State.COMPLETE && chunk.getMesh() != null) {
            ShaderProgram shader = ShaderManager.getInstance().getShaderProgram("chunk");
            // Transfer the world offset of the chunk to the shader for various effects
            shader.setFloat3("chunkOffset", (float) (chunk.getPos().x * Chunk.SIZE_X), (float) (chunk.getPos().y * Chunk.SIZE_Y), (float) (chunk.getPos().z * Chunk.SIZE_Z));
            shader.setFloat("animated", chunk.getAnimated() ? 1.0f: 0.0f);
            shader.setFloat("clipHeight", camera.getClipHeight());

            GL11.glPushMatrix();

            Vector3d cameraPosition = camera.getPosition();
            GL11.glTranslated(chunk.getPos().x * Chunk.SIZE_X - cameraPosition.x, chunk.getPos().y * Chunk.SIZE_Y - cameraPosition.y, chunk.getPos().z * Chunk.SIZE_Z - cameraPosition.z);

            for (int i = 0; i < VERTICAL_SEGMENTS; i++) {
                if (!chunk.getMesh()[i].isEmpty()) {
                    if (Config.getInstance().isRenderChunkBoundingBoxes()) {
                        chunk.getSubMeshAABB(i).renderLocally(1f);
                        _statRenderedTriangles += 12;
                    }

                    shader.enable();
                    chunk.getMesh()[i].render(phase);
                    _statRenderedTriangles += chunk.getMesh()[i].triangleCount();
                }
            }

            GL11.glPopMatrix();
        } else {
            _statChunkNotReady++;
        }
    }

    public float getRenderingLightValue() {
        return getRenderingLightValueAt(new Vector3f(getActiveCamera().getPosition()));
    }

    public float getRenderingLightValueAt(Vector3f pos) {
        float lightValueSun = _worldProvider.getSunlight(pos);
        lightValueSun /= 15.0f;
        lightValueSun *= getDaylight();
        float lightValueBlock = _worldProvider.getLight(pos);
        lightValueBlock /= 15f;

        return (float) TeraMath.clamp(lightValueSun + lightValueBlock * (1.0 - lightValueSun));
    }

    @Override
    public void update(float delta) {
        PerformanceMonitor.startActivity("Cameras");
        animateSpawnCamera(delta);
        _spawnCamera.update(delta);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Tick");
        updateTick(delta);
        PerformanceMonitor.endActivity();

        // Free unused space
        PerformanceMonitor.startActivity("Update Chunk Cache");
        _chunkProvider.update();
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Update Close Chunks");
        updateChunksInProximity(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Skysphere");
        _skysphere.update(delta);
        PerformanceMonitor.endActivity();

        if (_activeCamera != null) {
            _activeCamera.update(delta);
        }


        // And finally fire any active events
        PerformanceMonitor.startActivity("Fire Events");
        _worldTimeEventManager.fireWorldTimeEvents();
        PerformanceMonitor.endActivity();

        // Simulate world
        // TODO: Simulators
        PerformanceMonitor.startActivity("Liquid");
        //_worldProvider.getLiquidSimulator().simulate(false);
        PerformanceMonitor.endActivity();
        PerformanceMonitor.startActivity("Growth");
        // _worldProvider.getGrowthSimulator().simulate(false);
        PerformanceMonitor.endActivity();

        PerformanceMonitor.startActivity("Physics Renderer");
        _bulletRenderer.update(delta);
        PerformanceMonitor.endActivity();
    }

    private void renderDebugCollision(Camera camera) {
        if (_player != null && _player.isValid()) {
            AABBCollisionComponent collision = _player.getEntity().getComponent(AABBCollisionComponent.class);
            if (collision != null) {
                Vector3f worldLoc = _player.getPosition();
                AABB aabb = new AABB(new Vector3d(worldLoc), new Vector3d(collision.getExtents()));
                aabb.render(1f);
            }
        }

        List<BlockPosition> blocks = WorldUtil.gatherAdjacentBlockPositions(new Vector3f(camera.getPosition()));

        for (int i = 0; i < blocks.size(); i++) {
            BlockPosition p = blocks.get(i);
            Block block = getWorldProvider().getBlock(new Vector3f(p.x, p.y, p.z));
            for (AABB blockAABB : block.getColliders(p.x, p.y, p.z)) {
                blockAABB.render(1f);
            }
        }
    }

    private boolean isUnderwater() {
        Vector3d cameraPos = CoreRegistry.get(WorldRenderer.class).getActiveCamera().getPosition();
        Block block = CoreRegistry.get(WorldProvider.class).getBlock(new Vector3f(cameraPos));
        return block.isLiquid();
    }

    private void animateSpawnCamera(double delta) {
        if (_player == null || !_player.isValid())
            return;
        PlayerComponent player = _player.getEntity().getComponent(PlayerComponent.class);

        Vector3f cameraPosition = new Vector3f(player.spawnPosition);
        cameraPosition.y += 32;
        cameraPosition.x += Math.sin(getTick() * 0.0005f) * 32f;
        cameraPosition.z += Math.cos(getTick() * 0.0005f) * 32f;

        Vector3f playerToCamera = new Vector3f();
        playerToCamera.sub(getPlayerPosition(), cameraPosition);
        double distanceToPlayer = playerToCamera.length();

        Vector3f cameraDirection = new Vector3f();

        if (distanceToPlayer > 64.0) {
            cameraDirection.sub(player.spawnPosition, cameraPosition);
        } else {
            cameraDirection.set(playerToCamera);
        }

        cameraDirection.normalize();

        _spawnCamera.getPosition().set(cameraPosition);
        _spawnCamera.getViewingDirection().set(cameraDirection);
    }

    /**
     * Performs and maintains tick-based logic. If the game is paused this logic is not executed
     * First effect: update the _tick variable that animation is based on
     * Secondary effect: Trigger spawning (via PortalManager) once every second
     * Tertiary effect: Trigger socializing (via MobManager) once every 10 seconds
     */
    private void updateTick(float delta) {
        // Update the animation tick
        _tick += delta * 1000;

        // This block is based on seconds or less frequent timings
        if (_timer.getTimeInMs() - _lastTick >= 1000) {
            _tickTock++;
            _lastTick = _timer.getTimeInMs();

            // PortalManager ticks for spawning once a second
            _portalManager.tickSpawn();
        }
    }

    /**
     * Returns the maximum height at a given position.
     *
     * @param x The X-coordinate
     * @param z The Z-coordinate
     * @return The maximum height
     */
    public final int maxHeightAt(int x, int z) {
        for (int y = Chunk.SIZE_Y - 1; y >= 0; y--) {
            if (_worldProvider.getBlock(x, y, z).getId() != 0x0)
                return y;
        }

        return 0;
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the x-axis
     */
    private int calcCamChunkOffsetX() {
        return (int) (getActiveCamera().getPosition().x / Chunk.SIZE_X);
    }

    /**
     * Chunk position of the player.
     *
     * @return The player offset on the z-axis
     */
    private int calcCamChunkOffsetZ() {
        return (int) (getActiveCamera().getPosition().z / Chunk.SIZE_Z);
    }

    /**
     * Sets a new player and spawns him at the spawning point.
     *
     * @param p The player
     */
    public void setPlayer(LocalPlayer p) {
        _player = p;
        _chunkProvider.addRegionEntity(p.getEntity(), Config.getInstance().getActiveViewingDistance());
        updateChunksInProximity(true);
    }

    public void changeViewDistance(int viewingDistance) {
        _logger.log(Level.INFO, "New Viewing Distance: " + viewingDistance);
        if (_player != null) {
            _chunkProvider.addRegionEntity(_player.getEntity(), viewingDistance);
        }
        updateChunksInProximity(true);
    }

    public ChunkProvider getChunkProvider() {
        return _chunkProvider;
    }

    /**
     * Creates the first Portal if it doesn't exist yet
     */
    public void initPortal() {
        if (!_portalManager.hasPortal()) {
            Vector3d loc = new Vector3d(getPlayerPosition().x, getPlayerPosition().y + 4, getPlayerPosition().z);
            _logger.log(Level.INFO, "Portal location is" + loc);
            Vector3i pos = new Vector3i((int) loc.x - 1, (int) loc.y, (int) loc.z);
            while (true) {
                Block oldBlock = _worldProvider.getBlock(pos);
                if (_worldProvider.setBlock(pos, BlockManager.getInstance().getBlock("PortalBlock"), oldBlock)) {
                    break;
                }
                // TODO: keep trying, but make sure chunk is loaded.
                return;
            }
            _portalManager.addPortal(loc);
        }
    }

    /**
     * Disposes this world.
     */
    public void dispose() {
        _worldProvider.dispose();
        WorldInfo worldInfo = _worldProvider.getWorldInfo();
        try {
            WorldInfo.save(new File(PathManager.getInstance().getWorldSavePath(worldInfo.getTitle()), WorldInfo.DEFAULT_FILE_NAME), worldInfo);
        } catch (IOException e) {
            _logger.log(Level.SEVERE, "Failed to save world manifest");
        }

        AudioManager.getInstance().stopAllSounds();

        chunkStore.dispose();
        // TODO: this should be elsewhere, perhaps within the chunk cache.
        File chunkFile = new File(PathManager.getInstance().getWorldSavePath(_worldProvider.getTitle()), _worldProvider.getTitle() + ".dat");
        try {
            FileOutputStream fileOut = new FileOutputStream(chunkFile);
            BufferedOutputStream bos = new BufferedOutputStream(fileOut);
            ObjectOutputStream out = new ObjectOutputStream(bos);
            out.writeObject(chunkStore);
            out.close();
            bos.flush();
            bos.close();
            fileOut.close();
        } catch (IOException e) {
            _logger.log(Level.SEVERE, "Error saving chunks", e);
        }
    }

    /**
     * @return true if pregeneration is complete
     */
    public boolean pregenerateChunks() {
        boolean complete = true;
        int newChunkPosX = calcCamChunkOffsetX();
        int newChunkPosZ = calcCamChunkOffsetZ();
        int viewingDistance = Config.getInstance().getActiveViewingDistance();

        _chunkProvider.update();
        for (Vector3i pos : Region3i.createFromCenterExtents(new Vector3i(newChunkPosX, 0, newChunkPosZ), new Vector3i(viewingDistance / 2, 0, viewingDistance / 2))) {
            Chunk chunk = _chunkProvider.getChunk(pos);
            if (chunk == null || chunk.getChunkState() != Chunk.State.COMPLETE) {
                complete = false;
                continue;
            } else if (chunk.isDirty()) {
                WorldView view = _worldProvider.getWorldViewAround(chunk.getPos());
                if (view == null) {
                    continue;
                }
                chunk.setDirty(false);

                ChunkMesh[] newMeshes = new ChunkMesh[VERTICAL_SEGMENTS];
                for (int seg = 0; seg < VERTICAL_SEGMENTS; seg++) {
                    newMeshes[seg] = _chunkTesselator.generateMesh(view, chunk.getPos(), Chunk.SIZE_Y / VERTICAL_SEGMENTS, seg * (Chunk.SIZE_Y / VERTICAL_SEGMENTS));
                }

                chunk.setPendingMesh(newMeshes);

                if (chunk.getPendingMesh() != null) {

                    for (int j = 0; j < chunk.getPendingMesh().length; j++) {
                        chunk.getPendingMesh()[j].generateVBOs();
                    }
                    if (chunk.getMesh() != null) {
                        for (int j = 0; j < chunk.getMesh().length; j++) {
                            chunk.getMesh()[j].dispose();
                        }
                    }
                    chunk.setMesh(chunk.getPendingMesh());
                    chunk.setPendingMesh(null);
                }
                return false;
            }
        }
        return complete;
    }

    public void printScreen() {
        GL11.glReadBuffer(GL11.GL_FRONT);
        final int width = Display.getWidth();
        final int height = Display.getHeight();
        //int bpp = Display.getDisplayMode().getBitsPerPixel(); does return 0 - why?
        final int bpp = 4;
        final ByteBuffer buffer = BufferUtils.createByteBuffer(width * height * bpp); // hardcoded until i know how to get bpp
        GL11.glReadPixels(0, 0, width, height, (bpp == 3) ? GL11.GL_RGB : GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer);
        Runnable r = new Runnable() {
            @Override
            public void run() {
                Calendar cal = Calendar.getInstance();
                SimpleDateFormat sdf = new SimpleDateFormat("yyMMddHHmmssSSS");

                File file = new File(PathManager.getInstance().getScreensPath(), sdf.format(cal.getTime()) + ".png");
                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

                for (int x = 0; x < width; x++)
                    for (int y = 0; y < height; y++) {
                        int i = (x + width * y) * bpp;
                        int r = buffer.get(i) & 0xFF;
                        int g = buffer.get(i + 1) & 0xFF;
                        int b = buffer.get(i + 2) & 0xFF;
                        image.setRGB(x, height - (y + 1), (0xFF << 24) | (r << 16) | (g << 8) | b);
                    }

                try {
                    ImageIO.write(image, "png", file);
                } catch (IOException e) {
                    _logger.log(Level.WARNING, "Could not save image!", e);
                }
            }
        };

        CoreRegistry.get(TerasologyEngine.class).submitTask("Write screenshot", r);
    }


    @Override
    public String toString() {
        return String.format("world (biome: %s, time: %.2f, exposure: %.2f, sun: %.2f, cache: %fMb, dirty: %d, ign: %d, vis: %d, tri: %d, empty: %d, !ready: %d, seed: \"%s\", title: \"%s\")", getPlayerBiome(), _worldProvider.getTimeInDays(), PostProcessingRenderer.getInstance().getExposure(), _skysphere.getSunPosAngle(), _chunkProvider.size(), _statDirtyChunks, _statIgnoredPhases, _statVisibleChunks, _statRenderedTriangles, _statChunkMeshEmpty, _statChunkNotReady, _worldProvider.getSeed(), _worldProvider.getTitle());
    }

    public LocalPlayer getPlayer() {
        return _player;
    }

    public boolean isAABBVisible(AABB aabb) {
        return getActiveCamera().getViewFrustum().intersects(aabb);
    }

    public boolean isChunkValidForRender(Chunk c) {
        return _worldProvider.getWorldViewAround(c.getPos()) != null;
    }

    public boolean isChunkVisible(Chunk c) {
        return getActiveCamera().getViewFrustum().intersects(c.getAABB());
    }

    public double getDaylight() {
        return _skysphere.getDaylight();
    }

    public WorldBiomeProvider.Biome getPlayerBiome() {
        Vector3f pos = getPlayerPosition();
        return _worldProvider.getBiomeProvider().getBiomeAt(pos.x, pos.z);
    }

    public WorldProvider getWorldProvider() {
        return _worldProvider;
    }

    public BlockGrid getBlockGrid() {
        return _blockGrid;
    }

    public Skysphere getSkysphere() {
        return _skysphere;
    }

    public double getTick() {
        return _tick;
    }

    public List<Chunk> getChunksInProximity() {
        return _chunksInProximity;
    }

    public boolean isWireframe() {
        return _wireframe;
    }

    public void setWireframe(boolean _wireframe) {
        this._wireframe = _wireframe;
    }

    public BulletPhysicsRenderer getBulletRenderer() {
        return _bulletRenderer;
    }

    public Camera getActiveCamera() {
        return _activeCamera;
    }

    //TODO: Review
    public void setCameraMode(CAMERA_MODE mode) {
        _cameraMode = mode;
        switch (mode) {
            case PLAYER:
                _activeCamera = _defaultCamera;
                break;
            default:
                _activeCamera = _spawnCamera;
                break;
        }
    }

    public ChunkTessellator getChunkTesselator() {
        return _chunkTesselator;
    }
}
