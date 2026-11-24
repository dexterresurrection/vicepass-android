package uk.zjd.cloudpass.autofilltest

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.annotation.TargetApi
import androidx.autofill.inline.v1.InlineSuggestionUi

/** Эталонная служба: одна запись «ref» с обычным и встроенным видом (проверка самого эмулятора и клавиатуры). */
@TargetApi(30)
class RefAutofillService : AutofillService() {
    private fun ids(n: AssistStructure.ViewNode, out: MutableList<AutofillId>) {
        if (!n.autofillHints.isNullOrEmpty()) n.autofillId?.let { out.add(it) }
        for (i in 0 until n.childCount) ids(n.getChildAt(i), out)
    }

    @SuppressLint("RestrictedApi")
    @Suppress("DEPRECATION")
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        val st = request.fillContexts.last().structure
        val found = mutableListOf<AutofillId>()
        for (i in 0 until st.windowNodeCount) ids(st.getWindowNodeAt(i).rootViewNode, found)
        if (found.isEmpty()) { callback.onSuccess(null); return }
        val view = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply { setTextViewText(android.R.id.text1, "ref entry") }
        val ds = Dataset.Builder(view)
        found.forEachIndexed { i, id -> ds.setValue(id, AutofillValue.forText(if (i == 0) "refuser" else "refpass")) }
        if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest?.inlinePresentationSpecs?.firstOrNull()?.let { spec ->
            val pi = PendingIntent.getActivity(this, 0, Intent(this, LoginActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            ds.setInlinePresentation(InlinePresentation(InlineSuggestionUi.newContentBuilder(pi).setTitle("ref entry").build().slice, spec, false))
        }
        callback.onSuccess(FillResponse.Builder().addDataset(ds.build()).build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()
}
