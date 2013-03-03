/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package hcvengine;

import hcveasyncserver.HCVEAsyncServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jbox2d.callbacks.ContactImpulse;
import org.jbox2d.callbacks.ContactListener;
import org.jbox2d.collision.Manifold;
import org.jbox2d.collision.shapes.PolygonShape;
import org.jbox2d.common.Vec2;
import org.jbox2d.dynamics.Body;
import org.jbox2d.dynamics.BodyDef;
import org.jbox2d.dynamics.BodyType;
import org.jbox2d.dynamics.Fixture;
import org.jbox2d.dynamics.FixtureDef;
import org.jbox2d.dynamics.World;
import org.jbox2d.dynamics.contacts.Contact;
import org.jbox2d.dynamics.joints.MouseJoint;
import org.jbox2d.dynamics.joints.MouseJointDef;

/**
 *
 * @author ggc
 */
public class HCVEngine {    
    static final Logger logger = HCVEAsyncServer.logger;
    
    final World world;
    static final Vec2 gravity = new Vec2(0f, -10f);
    static final boolean doSleep = true;
    static Vec2 windowSize = new Vec2(800f, 600f);
    static float windowWidth = 10.f;
    static float timestamp = 1.f/30.f;
    static int velocityIterations = 6; //6
    static int positionIterations = 2; //2

    private Map<String, Body> vrObjectMap = null;
    private Map<String, MouseJoint> mjMap = null; 

    Body groundBody;
    Body mainBody;
    Fixture mainFixture;
    Vec2 ground_v1 = new Vec2(0.f, windowSize.y*0.1f);
    Vec2 ground_v2 = new Vec2(windowSize.x, windowSize.y*0.1f);


    public HCVEngine()
    {
        world = new World(gravity, doSleep);
        world.setContactListener(new VROContactListener());
        vrObjectMap = new HashMap<String, Body>();
        mjMap = new HashMap<String, MouseJoint>();
    }

    Vec2 getScreenToWorld(float screenX, float screenY) {
        //return new Vec2(screenX/30f, screenY/30f);        
        return new Vec2(windowSize.x*0.5f, windowSize.y*0.5f);
    }      
    
    public float[] getWindowSize(){
        return new float[]{windowSize.x, windowSize.y};
    }
       
    public float[] getLinearSpeed(String sUUID){
        Vec2 speed = vrObjectMap.get(sUUID).getLinearVelocity();
        return new float[]{speed.x, speed.y};
    }
    
    public float[] getPosition(String sUUID){
        
        if (!vrObjectMap.containsKey(sUUID)){
            logger.log(Level.INFO, "uuid not exists!?!!!");
        }
        
        Vec2 position = vrObjectMap.get(sUUID).getPosition();
        return new float[]{position.x, position.y};
    }
    
    // to-be-abandomed
    public void plotWindow()
    {
        List<Vec2> wallsInfo = new ArrayList<Vec2>();
        //bottom, top, left, and right wall
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowWidth*-0.5f));
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowWidth*0.5f));
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowSize.y+windowWidth*0.5f));
        wallsInfo.add(new Vec2(windowSize.x*0.5f, windowWidth*0.5f));
        wallsInfo.add(new Vec2(windowWidth*-0.5f,             windowSize.y*0.5f));
        wallsInfo.add(new Vec2(windowWidth*0.5f, windowSize.y*0.5f));
        wallsInfo.add(new Vec2(windowSize.x+windowWidth*0.5f, windowSize.y*0.5f));
        wallsInfo.add(new Vec2(windowWidth*0.5f, windowSize.y*0.5f));
        
        Iterator it = wallsInfo.iterator();
        while (it.hasNext()) {
            BodyDef wallBodyDef = new BodyDef();
            wallBodyDef.position.set((Vec2)it.next());
            Body wallBody = world.createBody(wallBodyDef);
            PolygonShape wallBox = new PolygonShape();
            Vec2 wallBoxSize = (Vec2)it.next();
            wallBox.setAsBox(wallBoxSize.x, wallBoxSize.y);
            
            wallBody.createFixture(wallBox, 0.f);
        }    
    }

    String createUUID() {
        return UUID.randomUUID().toString();
    }
    
    public void initWorld(){
        /*
         * Build the ground firstly before creating any objects
         * BodyDef, Body, shape(PolyonShape)
         */
        BodyDef def = new BodyDef();
        groundBody = world.createBody(def);
        
        PolygonShape shape = new PolygonShape();
        shape.setAsEdge(ground_v1, ground_v2);
        groundBody.createFixture(shape, 0.f);
    }
    
    public void step(){
        /*
         * Method: step
         * Parameters: 
         *   time step - how much time has passed in b/w each step
         *   velocity iteration - 
         *   position iteration - 
         *     these two iteration values determine how many time box2d will perform those calculation
         * Notes:
         *     the more iteration, the more accurate will be the result but of course more CPU box2d will be using,
         *     i.e., low values box2d will misss some collision or position incorrectly some objects
         */
        world.step(timestamp, velocityIterations, positionIterations);
        world.clearForces();
    }      
    
    public String initVOMain(float x, float y, float width, float height, float density){
        BodyDef def = new BodyDef();
        def.type = BodyType.DYNAMIC;
        def.position.set(x, y);
        Body body = world.createBody(def);
        
        PolygonShape shape = new PolygonShape();
        shape.setAsBox(width, height);
        Fixture fixture = body.createFixture(shape, density);
        mainBody = body;
        mainFixture = fixture;        
        
        String sUUID = createUUID();
        vrObjectMap.put(sUUID, body);
        return sUUID;
    }
    
    public String initFauxMJ(){
        return createUUID();
    }
    
    public String initMJ() {
        MouseJointDef def = new MouseJointDef();
        def.bodyA = groundBody;
        def.bodyB = mainBody;
        def.target.set(mainBody.getPosition());  // default position is mainOject's position
        def.maxForce = 1000f * mainBody.getMass();
        MouseJoint mj = (MouseJoint) world.createJoint(def);
        mainBody.setAwake(true);
        
        String sUUID = createUUID();
        mjMap.put(sUUID, mj);
        
        logger.log(Level.INFO, "A user join the game.");
        
        return sUUID;
    }    

    public void destroyFauxMJ(String sUUID){
    }
    
    public void destroyMJ(String sUUID) {
        if (mjMap.containsKey(sUUID)){
            MouseJoint mj = mjMap.get(sUUID);
            mjMap.remove(sUUID);
            world.destroyJoint(mj);

            logger.log(Level.INFO, "A user left the game.");            
        }
    }

    public void updateFauxMJ(String sUUID, float x, float y){
        
        if (x <= windowSize.x &&  y <= windowSize.y) {
            mainBody.setTransform(new Vec2(x,y), mainBody.getAngle());
        }
    }
    
    public void updateMJMove(String sUUID, float x, float y) {
        logger.log(Level.INFO, "A user[{0}], new position=({1},{2}), force=({3},{4})", 
                new Object[]{sUUID, x, y, mjMap.get(sUUID).getTarget().x, mjMap.get(sUUID).getTarget().y});  
        
        if (x <= windowSize.x &&  y <= windowSize.y) {
            MouseJoint mj = (MouseJoint)mjMap.get(sUUID);
            mj.setTarget(getScreenToWorld(x, y));
            //mj.setTarget(mainBody.getPosition());
        }        

        logger.log(Level.INFO, "A user updates finished.");        
    }
    
    /* Function: plotVRObject
     * Parameters: 
     *     Width, Height (Volume) - input size is the half of the indicated size.
     *     Position of X component, Y component (Position)
     *     Velocity of X component, Y component (Velocity)
     * Return: UUID of cretaed virtual object
     */
    public String plotVObject(float width, float height, float x, float y, float vx, float vy){
        Vec2 volume = new Vec2(width, height);
        Vec2 position = new Vec2(x, y);
        Vec2 velocity = new Vec2(vx, vy);
        
        BodyDef subjectBodyDef = new BodyDef();
        subjectBodyDef.type = BodyType.DYNAMIC;  // dynamic means it is subject to forces
        subjectBodyDef.position.set(position);
        Body subjectBody = world.createBody(subjectBodyDef);
        subjectBody.setUserData(subjectBody);
        PolygonShape dynamicBox = new PolygonShape();
        dynamicBox.setAsBox(volume.x, volume.y);
        
        FixtureDef fixtureDef = new FixtureDef();
        fixtureDef.shape = dynamicBox;
        fixtureDef.restitution = 0.7f;
        fixtureDef.density = 1.f;
        fixtureDef.friction = 0.f;
        subjectBody.createFixture(fixtureDef);
        subjectBody.setLinearVelocity(velocity);
        
        String sUUID = UUID.randomUUID().toString();
        vrObjectMap.put(sUUID, subjectBody);        
        return sUUID;
    }
}


class VROContactListener implements ContactListener {
    static final Logger logger = HCVEAsyncServer.logger;
    
    @Override
    public void beginContact(Contact contact) {
        //logger.log(Level.INFO, "Collisions");        
        Object userData = contact.getFixtureA().getBody().getUserData();

        if (userData != null) {
            //logger.log(Level.INFO, "A is subject.");
        }
        
        userData = null;
        userData = contact.getFixtureB().getBody().getUserData();
        
        if (userData != null) {
            //logger.log(Level.INFO, "B is subject.");
        }                
    }
    
    @Override
    public void endContact(Contact contact) 
    {
    }
    
    @Override
    public void postSolve(Contact contact, ContactImpulse impulse) 
    {
//        logger.log(Level.INFO, "postSolve.");        
    }
    
    @Override
    public void preSolve(Contact contact, Manifold oldManifold) {
//        logger.log(Level.INFO, "preSolve.");        
    }
    
}
