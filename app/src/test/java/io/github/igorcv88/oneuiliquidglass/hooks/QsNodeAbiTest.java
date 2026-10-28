package io.github.igorcv88.oneuiliquidglass.hooks;

import org.junit.Test;
import static org.junit.Assert.*;

/** Verifies descriptor matching and fail-closed behavior; these fixtures are not a DZIF DEX. */
public class QsNodeAbiTest {
    interface Scope {}
    static class Node {public void draw(Scope scope){}}
    interface Generic<T> {T create();void update(T node);}
    static class Element implements Generic<Node> {
        public Node create(){return new Node();}public void update(Node node){}
    }
    static class WrongCreate {public Object create(){return null;}public void update(Node node){}}
    static class WrongUpdate {public Node create(){return null;}public void update(Object node){}}
    static class OtherScope {}
    static class WrongReturnNode {public Object draw(Scope scope){return null;}}
    static class WrongReturnElement {public WrongReturnNode create(){return null;}public void update(WrongReturnNode node){}}
    static class StaticDrawNode {public static void draw(Scope scope){}}
    static class StaticDrawElement {public StaticDrawNode create(){return null;}public void update(StaticDrawNode node){}}
    @Test public void exactNodeAndScopeAreAcceptedAndGenericBridgesIgnored() throws Exception {
        QsNodeAbi abi=QsNodeAbi.resolve(Element.class,Node.class,Scope.class);
        assertEquals(Node.class,abi.create.getReturnType());assertFalse(abi.create.isBridge());
        assertEquals(Node.class,abi.update.getParameterTypes()[0]);assertEquals(Scope.class,abi.draw.getParameterTypes()[0]);
    }
    /** R8 may leave only the erased bridges (Modifier.Node): accepted, the node is still what they pass. */
    static class BaseNode {}
    static class ErasedNode extends BaseNode {public void draw(Scope scope){}}
    static class ErasedElement {public BaseNode create(){return new ErasedNode();}public void update(BaseNode node){}}
    @Test public void erasedBridgesAreAcceptedWhenTheTypedOnesAreGone() throws Exception {
        QsNodeAbi abi=QsNodeAbi.resolve(ErasedElement.class,ErasedNode.class,Scope.class);
        assertEquals(BaseNode.class,abi.create.getReturnType());
        assertEquals(BaseNode.class,abi.update.getParameterTypes()[0]);
    }
    @Test public void missingMemberNamesItAndListsTheCandidates() {
        try{QsNodeAbi.resolve(WrongUpdate.class,Node.class,Scope.class);fail();}
        catch(NoSuchMethodException e){
            assertTrue(e.getMessage(),e.getMessage().contains(".update candidates=[void(java.lang.Object)]"));
        }
    }
    @Test(expected=NoSuchMethodException.class) public void unknownCreateReturnIsRejected() throws Exception {
        QsNodeAbi.resolve(WrongCreate.class,Node.class,Scope.class);
    }
    @Test(expected=NoSuchMethodException.class) public void broadObjectUpdateIsRejected() throws Exception {
        QsNodeAbi.resolve(WrongUpdate.class,Node.class,Scope.class);
    }
    @Test(expected=NoSuchMethodException.class) public void differentDrawScopeIsRejected() throws Exception {
        QsNodeAbi.resolve(Element.class,Node.class,OtherScope.class);
    }
    @Test(expected=NoSuchMethodException.class) public void nonVoidDrawIsRejected() throws Exception {
        QsNodeAbi.resolve(WrongReturnElement.class,WrongReturnNode.class,Scope.class);
    }
    @Test(expected=NoSuchMethodException.class) public void staticDrawIsRejected() throws Exception {
        QsNodeAbi.resolve(StaticDrawElement.class,StaticDrawNode.class,Scope.class);
    }
}
