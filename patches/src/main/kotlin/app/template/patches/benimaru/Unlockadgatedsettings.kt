package app.morphe.patches.benimaru

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Compatibility for Benimaru Tool (com.benimaru.official).
 * Empty targets = any version of the package.
 */
val COMPATIBILITY_BENIMARU = Compatibility(
    name = "Benimaru Tool",
    packageName = "com.benimaru.official"
)

/**
 * Locates the private method that builds the premium-feature Sheet dialog
 * and contains the button label.
 * Four unique strings appear together only in this method.
 */
object HandlePremiumFeatureFingerprint : Fingerprint(
    definingClass = "Lcom/benimaru/official/MainActivity;",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Lkotlin/jvm/functions/Function0;"),
    filters = listOf(
        string("Watch ad to unlock"),
        string("Get Premium"),
        string("Not now"),
        string("Premium feature")
    )
)

/**
 * Locates the static lambda that currently launches the rewarded ad.
 * Matches by defining class + exact signature + presence of the showRewardedAd call.
 * Avoids sole reliance on the synthetic method name.
 */
object HandlePremiumFeatureLambdaFingerprint : Fingerprint(
    definingClass = "Lcom/benimaru/official/MainActivity;",
    accessFlags = listOf(AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Lkotlin/Unit;",
    parameters = listOf(
        "Lcom/benimaru/official/MainActivity;",
        "Lkotlin/jvm/functions/Function0;",
        "Lcom/google/android/material/button/MaterialButton;"
    ),
    filters = listOf(
        // Unique call that only this lambda makes
        // (the MethodReference will be matched by the filter implementation)
        string("it") // the checkNotNullParameter argument
    )
)

@Suppress("unused")
val unlockAdGatedSettingsPatch = bytecodePatch(
    name = "Unlock ad-gated settings",
    description = "Changes the 'Watch ad to unlock' button label to 'Unlock' and makes the button invoke the original feature callback directly without showing a rewarded advertisement.",
    default = true
) {
    compatibleWith(COMPATIBILITY_BENIMARU)

    execute {
        // ------------------------------------------------------------------
        // 1. Change the button label (only the const-string inside handlePremiumFeature)
        // ------------------------------------------------------------------
        HandlePremiumFeatureFingerprint.method.apply {
            implementation!!.instructions.forEachIndexed { index, instruction ->
                if (instruction.opcode == Opcode.CONST_STRING) {
                    val ref = (instruction as? ReferenceInstruction)?.reference
                    if (ref is StringReference && ref.string == "Watch ad to unlock") {
                        val register = (instruction as OneRegisterInstruction).registerA
                        replaceInstruction(
                            index,
                            "const-string v$register, \"Unlock\""
                        )
                    }
                }
            }
        }

        // ------------------------------------------------------------------
        // 2. Make the primary button unlock immediately
        // ------------------------------------------------------------------
        // Original instruction sequence inside handlePremiumFeature$lambda$6
        // (0-based indices after the method header):
        //
        // 0  const-string v0, "it"
        // 1  invoke-static {p2, v0}, ...checkNotNullParameter...
        // 2  new-instance p2, ...Lambda75;
        // 3  invoke-direct {p2, p1}, ...Lambda75;-><init>...
        // 4  invoke-direct {p0, p2}, ...->showRewardedAd(...)
        // 5  sget-object p0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
        // 6  return-object p0
        //
        // We surgically replace indices 2-4 with a single direct invoke of the
        // original Function0 (p1). Everything else (locals, null-check, return)
        // stays exactly as the compiler emitted it.

        val lambda = HandlePremiumFeatureLambdaFingerprint.method

        // Locate the invoke-direct that calls showRewardedAd
        val showAdIndex = lambda.implementation!!.instructions.indexOfFirst { insn ->
            if (insn.opcode != Opcode.INVOKE_DIRECT) return@indexOfFirst false
            val ref = (insn as? ReferenceInstruction)?.reference as? MethodReference
            ref?.name == "showRewardedAd" &&
            ref.definingClass == "Lcom/benimaru/official/MainActivity;"
        }

        if (showAdIndex == -1) {
            throw PatchException(
                "Could not locate the showRewardedAd call inside the premium-feature lambda"
            )
        }

        // The three instructions we want to replace are:
        //   new-instance  (showAdIndex - 2)
        //   invoke-direct <init>  (showAdIndex - 1)
        //   invoke-direct showRewardedAd  (showAdIndex)
        val start = showAdIndex - 2
        if (start < 0) {
            throw PatchException("Unexpected instruction layout around showRewardedAd")
        }

        // Remove the three instructions (from last to first to keep indices valid)
        lambda.removeInstructions(start, 3)

        // Insert the direct callback invocation at the same position
        lambda.addInstructions(
            start,
            """
                invoke-interface {p1}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
            """.trimIndent()
        )
    }
}
