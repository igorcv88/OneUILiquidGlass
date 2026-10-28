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

    /*
     * DZIF (device log 28/10 18:49:20): every hooked node has only
     * draw(androidx.compose.ui.node.LayoutNodeDrawScope), R8's narrowing of draw(ContentDrawScope)
     * to its only implementation. These fixtures mirror that shape for the four failing families
     * (BackgroundNode, TextStringSimpleNode, TextAnnotatedStringNode, PainterNode).
     */
    interface DrawScope {}
    interface ContentDrawScope extends DrawScope {}
    static final class LayoutNodeDrawScope implements ContentDrawScope {}
    static class ModifierNode {}
    static class BackgroundNode extends ModifierNode {public void draw(LayoutNodeDrawScope s){}}
    static class BackgroundElement {public BackgroundNode create(){return null;}public void update(BackgroundNode n){}}
    static class TextStringSimpleNode extends ModifierNode {public void draw(LayoutNodeDrawScope s){}}
    static class TextStringSimpleElement {public ModifierNode create(){return null;}public void update(ModifierNode n){}}
    static class TextAnnotatedStringNode extends ModifierNode {public void draw(LayoutNodeDrawScope s){}}
    static class TextAnnotatedStringElement {public TextAnnotatedStringNode create(){return null;}public void update(TextAnnotatedStringNode n){}}
    static class PainterNode extends ModifierNode {public void draw(LayoutNodeDrawScope s){}}
    static class PainterElement {public PainterNode create(){return null;}public void update(ModifierNode n){}}
    @Test public void dzifNarrowedLayoutNodeDrawScopeIsAcceptedForEveryFailedFamily() throws Exception {
        Class<?>[][] families={{BackgroundElement.class,BackgroundNode.class},{TextStringSimpleElement.class,TextStringSimpleNode.class},
                {TextAnnotatedStringElement.class,TextAnnotatedStringNode.class},{PainterElement.class,PainterNode.class}};
        for(Class<?>[] f:families){
            QsNodeAbi abi=QsNodeAbi.resolve(f[0],f[1],ContentDrawScope.class);
            assertEquals(f[1].getSimpleName(),LayoutNodeDrawScope.class,abi.draw.getParameterTypes()[0]);
            assertEquals(f[1],abi.draw.getDeclaringClass());
        }
    }
    /** When both exist, the exact ContentDrawScope method wins over the narrowed one. */
    static class BothNode {public void draw(ContentDrawScope s){}public void draw(LayoutNodeDrawScope s){}}
    static class BothElement {public BothNode create(){return null;}public void update(BothNode n){}}
    @Test public void exactScopeIsPreferredOverANarrowedOne() throws Exception {
        assertEquals(ContentDrawScope.class,QsNodeAbi.resolve(BothElement.class,BothNode.class,ContentDrawScope.class).draw.getParameterTypes()[0]);
    }
    /** A scope unrelated to ContentDrawScope, or Object, is still rejected. */
    static class ObjectDrawNode {public void draw(Object s){}}
    static class ObjectDrawElement {public ObjectDrawNode create(){return null;}public void update(ObjectDrawNode n){}}
    static class UnrelatedNode {public void draw(OtherScope s){}}
    static class UnrelatedElement {public UnrelatedNode create(){return null;}public void update(UnrelatedNode n){}}
    @Test public void objectOrUnrelatedDrawParameterIsRejected() {
        try{QsNodeAbi.resolve(ObjectDrawElement.class,ObjectDrawNode.class,ContentDrawScope.class);fail();}catch(NoSuchMethodException expected){}
        try{QsNodeAbi.resolve(UnrelatedElement.class,UnrelatedNode.class,ContentDrawScope.class);fail();}catch(NoSuchMethodException expected){}
        assertFalse(QsNodeAbi.drawScope(Object.class,ContentDrawScope.class));
        assertTrue(QsNodeAbi.drawScope(DrawScope.class,ContentDrawScope.class));
        assertTrue(QsNodeAbi.drawScope(LayoutNodeDrawScope.class,ContentDrawScope.class));
    }
}
