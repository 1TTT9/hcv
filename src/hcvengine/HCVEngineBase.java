/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcvengine;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jbox2d.callbacks.ContactImpulse;
import org.jbox2d.callbacks.ContactListener;
import org.jbox2d.callbacks.QueryCallback;
import org.jbox2d.collision.AABB;
import org.jbox2d.collision.Collision;
import org.jbox2d.collision.Collision.PointState;
import org.jbox2d.collision.Manifold;
import org.jbox2d.collision.WorldManifold;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyDef;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.Fixture;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.contacts.Contact;
import org.jbox2d.dynamics.joints.MouseJoint;
import org.jbox2d.dynamics.joints.MouseJointDef;

/**
 *
 * @author ggc
 */
public abstract class HCVEngineBase implements ContactListener{
    static final Logger logger = hcveasyncserver.HCVEAsyncServer.logger;
 
    private float TIMESTEP = 1f/30f;
    private int VELOCITYITERATIONS = 3;
    private int POSITIONITERATIONS = 8;
    
    protected World m_world;
    private Body groundBody;

    private final LinkedList<QueueItem> inputQueue;
    public final Map<String, HCVDevice> devices;
    
    public boolean isInitialized = false;

    
    public HCVEngineBase(){
        inputQueue = new LinkedList<>();
        devices = new HashMap<>();
    }
    
    
    public void init(){
        Vec2 gravity = new Vec2(0, -2f);
        m_world = new World(gravity, true);
        
        BodyDef def = new BodyDef();
        groundBody = m_world.createBody(def);
        
        init(m_world);
    }
    
    public void init(World argWorld){
        argWorld.setContactListener(this);
        
        initEngine();
        isInitialized = true;
    }
    
    /*
     *  Get the current world
     *  @return
     */
    public World getWorld() {
        return m_world;
    }
    
    /*
     *  Get the ground body in the world, used for some joints
     *  @return
     */
    public Body getGroundBody() {
        return groundBody;
    }
    
    /*
     *  Get device with UUID u in the world
     *  @return
     */
    public HCVDevice getDevice(String u){
        HCVDevice dev = null;
        if (devices.containsKey(u)){
            dev = devices.get(u);
        }
        return dev;
    }

    public int getDeviceStateByUUID(String u){
        if (devices.containsKey(u)){
            return devices.get(u).getDeviceStatus();
        }else{
            return -1;
        }
    }
    
    
    public void update() {
        if (!inputQueue.isEmpty()){
            synchronized (inputQueue){
                while (!inputQueue.isEmpty()) {
                    QueueItem item = inputQueue.poll();
                    switch(item.type) {
                        case HCVDeviceJoin:
                            hcvDeviceJoin(item.uuid, item.p);
                            break;
                        case HCVDeviceIdle:
                            hcvDeviceIdle(item.uuid, item.p);
                            break;
                        case HCVDeviceMove:
                            hcvDeviceMove(item.uuid, item.p);
                            break;
                        case HCVDeviceQuit:
                            hcvDeviceQuit(item.uuid, item.p);
                            break;
                    }
                }
            }
        }
        step();
    }
    
    
    public synchronized void step() {
        m_world.step(TIMESTEP, VELOCITYITERATIONS, POSITIONITERATIONS);
    }

    public void queueDeviceJoin(String u, Vec2 p){
        synchronized(inputQueue){
            inputQueue.addLast(new QueueItem(QueueItemType.HCVDeviceJoin, u, p));
        }
    }

    public void queueDeviceIdle(String u, Vec2 p){
        synchronized(inputQueue){
            inputQueue.addLast(new QueueItem(QueueItemType.HCVDeviceIdle, u, p));
        }
    }
    
    public void queueDeviceMove(String u, Vec2 p){
        synchronized(inputQueue){
            inputQueue.addLast(new QueueItem(QueueItemType.HCVDeviceMove, u, p));
        }
    }
    
    public void queueDeviceQuit(String u, Vec2 p){
        synchronized(inputQueue){
            inputQueue.addLast(new QueueItem(QueueItemType.HCVDeviceQuit, u, p));
        }
    }
    
    private final AABB querryAABB = new AABB();
    private final BaseQueryCallBack cb = new BaseQueryCallBack();
    /*
     *  Called when remote-device joins
     *  @param u, p
     */
    public void hcvDeviceJoin(String u, Vec2 p) {
        
        if (devices.containsKey(u)){
            return;
        }
        devices.put(u, new HCVDevice(u, p));        
    }
    
    /*
     *  Called when remote-device idles
     *  @param u, p
     */
    public void hcvDeviceIdle(String u, Vec2 p){
        if (!devices.containsKey(u)){
            return;
        }
        
        HCVDevice dev = devices.get(u);
        if (dev.mj != null){
            m_world.destroyJoint(dev.mj);
            }
        dev.mj = null;
        dev.mouseworld.set(p);
        dev.state = ConnDeviceState.IDLE;
    }
       
    /*
     *  Called when remote-device's movement occurs
     *  @param u, p
     */
    public void hcvDeviceMove(String u, Vec2 p) {
        if (!devices.containsKey(u)){
            return;
        }
        HCVDevice dev = devices.get(u);        
        dev.mouseworld.set(p);
        
        if (dev.mj != null){
            dev.mj.setTarget(p);
        }else{
            dev.state = ConnDeviceState.IDLE;
            
            querryAABB.lowerBound.set(p.x - 0.001f, p.y - 0.001f);
            querryAABB.upperBound.set(p.x + 0.001f, p.y + 0.001f);
            cb.point.set(p);
            cb.fixture = null;
            m_world.queryAABB(cb, querryAABB);
            if (cb.fixture != null) {
                Body body = cb.fixture.getBody();
                MouseJointDef def = new MouseJointDef();
                def.bodyA = groundBody;
                def.bodyB = body;
                def.target.set(p);
                def.maxForce = 1000f * body.getMass();
                dev.mj = (MouseJoint) m_world.createJoint(def);
                body.setAwake(true);
                
                dev.state = ConnDeviceState.MOVE;
            }
        }
    }
    
    /*
     *  Called for destroying a joint
     */
    public void hcvDeviceQuit(String u, Vec2 p) {
        if (!devices.containsKey(u)){
            return;
        }
        HCVDevice dev = devices.get(u);
        if (dev.mj != null){
            m_world.destroyJoint(dev.mj);
        }
        dev.mj = null;
        devices.remove(u);
    }

    public boolean isDeviceExist(String u){
        return devices.containsKey(u);
    }      
    
    public void beginContact(Contact contact){
    }
    
    public void endContact(Contact contact){
    }
    
    public void postSolve(Contact contact, ContactImpulse impulse) {
    }        
    
    public void preSolve(Contact contact, Manifold oldManifold) {
    }
    
    public abstract void initEngine();
    
}


enum QueueItemType {
    HCVDeviceJoin, HCVDeviceMove, HCVDeviceIdle, HCVDeviceQuit
}

class QueueItem {
    public QueueItemType type;
    public Vec2 p;
    public String uuid;
    
    public QueueItem(QueueItemType t, String u, Vec2 pos){
        type = t;
        uuid = u;
        p = pos;
    }    
}


class BaseQueryCallBack implements QueryCallback {
    public final Vec2 point;
    public Fixture fixture;
    
    public BaseQueryCallBack() {
        point = new Vec2();
        fixture = null;
    }
    public boolean reportFixture(Fixture argFixture) {
        Body body = argFixture.getBody();
        if (body.getType() == BodyType.DYNAMIC) {
            boolean inside = argFixture.testPoint(point);
            if (inside) {
                fixture = argFixture;
                return false;
            }
        }
        return true;
    }
}


enum ConnDeviceState {
    INIT, SYNC, IDLE, MOVE
}

class HCVDevice{
    public final String uuid;
    public final Vec2 mouseworld;
    public MouseJoint mj;
    public ConnDeviceState state;

    public HCVDevice(String u, Vec2 v) {
        uuid = u;
        mj = null;
        mouseworld = new Vec2(v);
        state = ConnDeviceState.SYNC;
    }    
    
    public HCVDevice(String u, float x, float y) {
        uuid = u;
        mj = null;
        mouseworld = new Vec2(x,y);
        state = ConnDeviceState.SYNC;        
    }
    
    public int getDeviceStatus(){
        int ret = -1;
        switch(state){
            case INIT:
                ret = 0;
                break;
            case SYNC:
                ret = 1;
                break;
            case IDLE:
                ret = 2;
                break;
            case MOVE:
                ret = 3;
                break;
        }
        return ret;
    }
    
    
}


