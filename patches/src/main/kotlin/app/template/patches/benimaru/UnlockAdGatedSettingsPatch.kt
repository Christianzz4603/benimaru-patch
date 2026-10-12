package app.template.patches.benimaru

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.removeInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.PatchException
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
 * Matches by defining class + exact signature + the distinctive "it" null-check string.
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
        string("it")
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
        // 1. Change the button label
        //    Original: const-string v4, "Watch ad to unlock"
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
        //
        // Original handlePremiumFeature$lambda$6 body (from decompiled Smali):
        //
        //   .locals 1
        //   const-string v0, "it"
        //   invoke-static {p2, v0}, Lkotlin/jvm/internal/Intrinsics;->checkNotNullParameter(...)
        //   new-instance p2, ...Lambda75;
        //   invoke-direct {p2, p1}, ...Lambda75;-><init>(Lkotlin/jvm/functions/Function0;)V
        //   invoke-direct {p0, p2}, ...->showRewardedAd(Lkotlin/jvm/functions/Function0;)V
        //   sget-object p0, Lkotlin/Unit;->INSTANCE:Lkotlin/Unit;
        //   return-object p0
        //
        // p0 = MainActivity
        // p1 = original Function0 (the real feature unlock/action callback)
        // p2 = MaterialButton
        //
        // We surgically remove the three instructions that create the wrapper
        // and call showRewardedAd, then insert a direct invoke of p1.
        // ------------------------------------------------------------------
        val lambda = HandlePremiumFeatureLambdaFingerprint.method

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

        // new-instance + invoke-direct <init> + invoke-direct showRewardedAd
        val start = showAdIndex - 2
        if (start < 0) {
            throw PatchException("Unexpected instruction layout around showRewardedAd")
        }

        lambda.removeInstructions(start, 3)

        lambda.addInstructions(
            start,
            """
                invoke-interface {p1}, Lkotlin/jvm/functions/Function0;->invoke()Ljava/lang/Object;
            """.trimIndent()
        )
    }
}
