package com.seedsigner.app

import com.seedsigner.app.views.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Constructor

/**
 * Guards `Controller.instantiateView` (the port of Python's
 * `View_cls(**view_args)`).
 *
 * History: the first matcher required an EXACT key-count match and silently
 * fell back to the no-arg constructor for any call site whose viewArgs map
 * did not carry every constructor param name — NPEs deep in the flow
 * (see REASONING.md). The current matcher picks the most specific
 * constructor whose parameters are ALL named in viewArgs.
 *
 * The invariant that makes that matcher unambiguous: every concrete View has
 * at most ONE real (non-synthetic) constructor. With a single constructor
 * there is no "smaller" constructor a partial key set could drift into —
 * either all params are covered (the real ctor is used) or the no-arg
 * fallback is used, which is the documented contract for views that read
 * the `viewArgs` field.
 *
 * When adding a new View, add its class to [viewClasses] — that is the
 * point of this test existing.
 */
class ViewInstantiationTest {

    private val viewClasses: List<Class<*>> = listOf(
        // MainViews
        OpeningSplashView::class.java,
        MainMenuView::class.java,
        // ErrorViews
        NotYetImplementedView::class.java,
        ErrorView::class.java,
        UnhandledExceptionView::class.java,
        // QrDisplayView
        QrDisplayView::class.java,
        // ScanViews
        ScanView::class.java,
        ScanResultView::class.java,
        ScanSpecterPsbtView::class.java,
        ScanBbqrPsbtView::class.java,
        // SeedViews
        SeedsMenuView::class.java,
        LoadSeedView::class.java,
        SeedOptionsView::class.java,
        SeedMnemonicEntryView::class.java,
        SeedMnemonicInvalidView::class.java,
        SeedFinalizeView::class.java,
        SeedAddPassphraseView::class.java,
        SeedAddPassphraseExitDialogView::class.java,
        SeedReviewPassphraseView::class.java,
        SeedDiscardView::class.java,
        // SeedBackupViews
        SeedElectrumMnemonicStartView::class.java,
        SeedBackupView::class.java,
        SeedTranscribeSeedQRFormatView::class.java,
        SeedTranscribeSeedQRWarningView::class.java,
        SeedTranscribeSeedQRWholeQRView::class.java,
        SeedTranscribeSeedQRZoomedInView::class.java,
        SeedTranscribeSeedQRConfirmQRPromptView::class.java,
        SeedTranscribeSeedQRConfirmScanView::class.java,
        SeedTranscribeSeedQRConfirmWrongSeedView::class.java,
        SeedTranscribeSeedQRConfirmSuccessView::class.java,
        SeedTranscribeSeedQRConfirmInvalidQRView::class.java,
        // ToolsViews
        ToolsMenuView::class.java,
        ToolsImageEntropyLivePreviewView::class.java,
        ToolsImageEntropyFinalImageView::class.java,
        ToolsImageEntropyMnemonicLengthView::class.java,
        ToolsDiceEntropyMnemonicLengthView::class.java,
        ToolsDiceEntropyEntryView::class.java,
        ToolsCalcFinalWordNumWordsView::class.java,
        ToolsCalcFinalWordFinalizePromptView::class.java,
        ToolsCalcFinalWordCoinFlipsView::class.java,
        ToolsCalcFinalWordShowFinalWordView::class.java,
        ToolsCalcFinalWordDoneView::class.java,
        // ExplorerViews
        ToolsAddressExplorerSelectSourceView::class.java,
        ToolsAddressExplorerAddressTypeView::class.java,
        ToolsAddressExplorerAddressListView::class.java,
        ToolsAddressExplorerAddressView::class.java,
        // AddressViews
        ScanAddressView::class.java,
        AddressVerificationStartView::class.java,
        AddressVerificationSigTypeView::class.java,
        SeedAddressVerificationView::class.java,
        SeedAddressVerificationSuccessView::class.java,
        // DescriptorViews
        ScanWalletDescriptorView::class.java,
        LoadMultisigWalletDescriptorView::class.java,
        MultisigWalletDescriptorView::class.java,
        // MessageSignViews
        SeedSignMessageStartView::class.java,
        SeedSignMessageConfirmMessageView::class.java,
        SeedSignMessageConfirmAddressView::class.java,
        SeedSignMessageSignedMessageQRView::class.java,
        // Bip85Views
        SeedBIP85SelectNumWordsView::class.java,
        SeedBIP85SelectChildIndexView::class.java,
        SeedBIP85InvalidChildIndexView::class.java,
        // SeedWordsViews
        SeedWordsWarningView::class.java,
        SeedWordsView::class.java,
        SeedWordsBackupTestPromptView::class.java,
        SeedWordsBackupTestView::class.java,
        SeedWordsBackupTestMistakeView::class.java,
        SeedWordsBackupTestSuccessView::class.java,
        // XpubViews
        SeedExportXpubSigTypeView::class.java,
        SeedExportXpubScriptTypeView::class.java,
        SeedExportXpubCustomDerivationView::class.java,
        SeedExportXpubQRFormatView::class.java,
        SeedExportXpubWarningView::class.java,
        SeedExportXpubDetailsView::class.java,
        SeedExportXpubQRDisplayView::class.java,
        // PsbtViews
        PSBTSelectSeedView::class.java,
        PSBTOverviewView::class.java,
        PSBTUnsupportedScriptTypeWarningView::class.java,
        PSBTNoChangeWarningView::class.java,
        PSBTHighFeeWarningView::class.java,
        PSBTMathView::class.java,
        PSBTAddressDetailsView::class.java,
        PSBTChangeDetailsView::class.java,
        PSBTSeedCannotSignView::class.java,
        PSBTOutputOwnershipClaimFailedView::class.java,
        PSBTInputOwnershipClaimFailedView::class.java,
        PSBTSurplusDerivationPathsView::class.java,
        PSBTMixedDerivationPathTypesView::class.java,
        PSBTOutputOwnershipContradictionView::class.java,
        PSBTAddressVerificationFailedView::class.java,
        PSBTOpReturnView::class.java,
        PSBTFinalizeView::class.java,
        PSBTSignedQRDisplayView::class.java,
        PSBTSigningErrorView::class.java,
        // SettingsViews
        SettingsMenuView::class.java,
        SettingsEntryUpdateView::class.java,
        VersionView::class.java,
        SettingsIngestSettingsQRView::class.java,
    )

    /**
     * Real constructors. A View with all-defaulted params compiles to:
     *   (1) a no-arg constructor,
     *   (2) the named constructor (real parameter names, -java-parameters),
     *   (3) synthetic defaulting bridges named arg0/arg1/... or $-prefixed.
     * Returns (1) + (2) only.
     */
    private fun realCtors(cls: Class<*>): List<Constructor<*>> =
        cls.declaredConstructors.filter { c ->
            c.parameters.isEmpty() || c.parameters.all {
                it.name.isNotEmpty() &&
                    !it.name.startsWith("$") &&
                    !Regex("arg\\d+").matches(it.name)
            }
        }

    @Test
    fun everyViewHasAtMostOneNamedConstructor() {
        // The invariant that makes Controller.instantiateView's
        // kwargs-style matching unambiguous: at most ONE named (non-empty)
        // constructor per View. With two, a partial viewArgs map could
        // silently bind to the smaller one (the E2E bug family).
        viewClasses.forEach { cls ->
            val named = realCtors(cls).filter { it.parameters.isNotEmpty() }
            assertTrue(
                "${cls.simpleName} has ${named.size} named constructors — " +
                    "Controller.instantiateView could silently pick the wrong one",
                named.size <= 1
            )
            // Parameter names are the routing keys; duplicates would be
            // unresolvable at the call sites.
            named.singleOrNull()?.parameters?.map { it.name }?.let { names ->
                assertEquals(names.size, names.toSet().size)
            }
        }
    }

    private fun instantiate(dest: Destination): View {
        val m = Controller::class.java.getDeclaredMethod("instantiateView", Destination::class.java)
        m.isAccessible = true
        return m.invoke(Controller.instance, dest) as View
    }

    @Test
    fun kwargsMatchingPicksTheRealConstructorForCoveredParams() {
        // The E2E regression: `SeedBackupView` → `SeedWordsWarningView`
        // passes only `seedIndex`; the 4-param constructor (all defaulted)
        // must be the one picked, with `seedIndex` bound.
        val v = instantiate(Destination(
            SeedWordsWarningView::class.java,
            viewArgs = mapOf("seedIndex" to 0)
        ))
        val f = v.javaClass.getDeclaredField("seedIndex")
        f.isAccessible = true
        assertEquals(0, f.get(v))

        // Full key set: every constructor param covered.
        val w = instantiate(Destination(
            SeedWordsView::class.java,
            viewArgs = mapOf(
                "seed" to null, "seedIndex" to 0, "pageIndex" to 3,
                "bip85ChildIndex" to null, "bip85NumWords" to null
            )
        ))
        val pf = w.javaClass.getDeclaredField("pageIndex")
        pf.isAccessible = true
        assertEquals(3, pf.get(w))
    }
}
