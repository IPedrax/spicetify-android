package app.spicetify.patches.spotify.extensions

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val PROVIDED = "Lcom/spotify/casita/v1/resolved/Provided;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"

class ExtensionsPatchTest {
    @Test
    fun `reports each field number that changed`() {
        val apk = mapOf("A#X" to 1, "A#Y" to 3)
        assertEquals(listOf("A#Y expected 2, found 3"),
            fieldNumberMismatches(mapOf("A#X" to 1, "A#Y" to 2), apk::get))
    }

    @Test
    fun `reports a field whose class is missing`() {
        assertEquals(listOf("A#X missing"), fieldNumberMismatches(mapOf("A#X" to 1)) { null })
    }

    @Test
    fun `hooks the return that follows initializeScheduling`() {
        assertEquals(2, bridgeHookIndex(listOf(schedule, schedule, returnVoid)))
    }

    @Test
    fun `refuses a constructor whose ending changed`() {
        assertNull(bridgeHookIndex(listOf(returnVoid)))
        assertNull(bridgeHookIndex(listOf(schedule, returnVoid, schedule, returnVoid)))
        assertNull(bridgeHookIndex(listOf(invoke(Opcode.INVOKE_DIRECT), returnVoid)))
        assertNull(bridgeHookIndex(listOf(invoke(Opcode.INVOKE_VIRTUAL, "shutdown"), returnVoid)))
        assertNull(bridgeHookIndex(listOf(invoke(Opcode.INVOKE_VIRTUAL, owner = "Lp/other;"), returnVoid)))
    }

    @Test
    fun `accepts the menu model the menu hooks insert before`() {
        assertTrue(isMenuModel(type(Opcode.NEW_INSTANCE, 1, "Lp/krj;")))
    }

    @Test
    fun `refuses any other instruction at a menu hook`() {
        assertFalse(isMenuModel(null))
        assertFalse(isMenuModel(type(Opcode.NEW_INSTANCE, 2, "Lp/krj;")))
        assertFalse(isMenuModel(type(Opcode.NEW_INSTANCE, 1, "Lp/other;")))
        assertFalse(isMenuModel(type(Opcode.CONST_CLASS, 1, "Lp/krj;")))
        assertFalse(isMenuModel(returnVoid))
    }

    @Test
    fun `accepts the instruction a Hide podcasts hook expects at its index`() {
        val section = "Lcom/spotify/casita/v1/resolved/Section;"
        val mapSection = ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 4, 5, 0, 0, 0,
            ImmutableMethodReference("Lp/mz1;", "O", listOf(section), "Lp/n920;"))
        assertTrue(isHookSite(mapSection, Opcode.INVOKE_VIRTUAL, "Lp/mz1;->O($section)Lp/n920;"))
        assertTrue(isHookSite(type(Opcode.NEW_INSTANCE, 0, ARRAY_LIST), Opcode.NEW_INSTANCE, ARRAY_LIST))
        assertTrue(isHookSite(ImmutableInstruction22x(Opcode.MOVE_OBJECT_FROM16, 0, 46), Opcode.MOVE_OBJECT_FROM16))
        assertTrue(isHookSite(field(Opcode.IPUT_OBJECT, 1, 0, "Lp/ipy;", "a", ARRAY_LIST),
            Opcode.IPUT_OBJECT, "Lp/ipy;->a:$ARRAY_LIST"))
    }

    @Test
    fun `refuses any other instruction at a Hide podcasts hook`() {
        assertFalse(isHookSite(null, Opcode.MOVE_OBJECT_FROM16))
        assertFalse(isHookSite(returnVoid, Opcode.MOVE_OBJECT_FROM16))
        assertFalse(isHookSite(type(Opcode.NEW_INSTANCE, 0, "Ljava/util/LinkedList;"), Opcode.NEW_INSTANCE, ARRAY_LIST))
        assertFalse(isHookSite(type(Opcode.CONST_CLASS, 0, ARRAY_LIST), Opcode.NEW_INSTANCE, ARRAY_LIST))
        assertFalse(isHookSite(type(Opcode.NEW_INSTANCE, 0, ARRAY_LIST), Opcode.NEW_INSTANCE))
        assertFalse(isHookSite(field(Opcode.IPUT_OBJECT, 1, 0, "Lp/ipy;", "b", ARRAY_LIST),
            Opcode.IPUT_OBJECT, "Lp/ipy;->a:$ARRAY_LIST"))
    }

    @Test
    fun `accepts the items getter P2 hooks`() {
        assertTrue(isItemsGetter(listOf(items(0), returnObject(0))))
    }

    @Test
    fun `refuses an items getter of any other shape`() {
        assertFalse(isItemsGetter(listOf(items(0))))
        assertFalse(isItemsGetter(listOf(items(0), returnObject(0), returnObject(0))))
        assertFalse(isItemsGetter(listOf(returnObject(0), items(0))))
        assertFalse(isItemsGetter(listOf(items(1), returnObject(1))))
        assertFalse(isItemsGetter(listOf(items(0), returnObject(1))))
        assertFalse(isItemsGetter(listOf(items(0), ImmutableInstruction11x(Opcode.THROW, 0))))
        assertFalse(isItemsGetter(listOf(field(Opcode.IGET_OBJECT, 0, 1, PROVIDED, "other_", "Lp/ih40;"), returnObject(0))))
    }

    /** `iget-object v[register], v1, Provided;->items_`, v1 being `this` in the two-register getter. */
    private fun items(register: Int) = field(Opcode.IGET_OBJECT, register, 1, PROVIDED, "items_", "Lp/ih40;")

    private fun returnObject(register: Int) = ImmutableInstruction11x(Opcode.RETURN_OBJECT, register)

    private fun field(opcode: Opcode, value: Int, instance: Int, owner: String, name: String, type: String) =
        ImmutableInstruction22c(opcode, value, instance, ImmutableFieldReference(owner, name, type))

    private fun type(opcode: Opcode, register: Int, type: String) =
        ImmutableInstruction21c(opcode, register, ImmutableTypeReference(type))

    private val returnVoid = ImmutableInstruction10x(Opcode.RETURN_VOID)
    private val schedule = invoke(Opcode.INVOKE_VIRTUAL)

    private fun invoke(
        opcode: Opcode,
        name: String = "initializeScheduling",
        owner: String = "Lcom/spotify/cosmos/cosmosimpl/NativeRouter;",
    ) = ImmutableInstruction35c(opcode, 2, 3, 0, 0, 0, 0,
        ImmutableMethodReference(owner, name, listOf("Lcom/spotify/cosmos/cosmosimpl/Scheduler;"), "V"))
}
