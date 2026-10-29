package app.thirdear.activities

import android.os.Bundle
import android.view.View
import android.widget.PopupMenu
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.helpers.NavigationIcon
import app.thirdear.R
import app.thirdear.actions.ActionPlan
import app.thirdear.actions.ActionStore
import app.thirdear.databinding.ActivityActionsBinding
import app.thirdear.databinding.ItemActionRowBinding
import app.thirdear.dialogs.ActionEditDialog
import app.thirdear.extensions.config
import app.thirdear.extensions.describe
import app.thirdear.extensions.recordActions
import app.thirdear.pipeline.Transcriber
import app.thirdear.tiles.ActionTileService

/** The user's actions in button order: tap to edit, move or delete; add new ones. */
class ActionsActivity : SimpleActivity() {

    private lateinit var binding: ActivityActionsBinding
    private lateinit var store: ActionStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActionsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        store = ActionStore(this)
        setupEdgeToEdge(padBottomSystem = listOf(binding.actionsScrollview))
        setupMaterialScrollListener(binding.actionsScrollview, binding.actionsAppbar)
        binding.actionsAdd.setOnClickListener {
            ActionEditDialog(this, null) {
                store.upsert(it)
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.actionsAppbar, NavigationIcon.Arrow)
        refresh()
    }

    private fun refresh() {
        val transcriber = Transcriber(this).available(config.transcriber)
        val actions = recordActions()
        binding.actionsList.removeAllViews()
        for (a in actions) {
            val row = ItemActionRowBinding.inflate(layoutInflater, binding.actionsList, false)
            row.actionRowName.text = a.name
            row.actionRowDesc.text = describe(a)
            val problems = ActionPlan.of(a).problems(transcriber)
            row.actionRowProblem.text = problems.joinToString("\n") {
                getString(
                    when (it) {
                        ActionPlan.Problem.NO_NAME -> R.string.te_problem_no_name
                        ActionPlan.Problem.NO_GROUP -> R.string.te_problem_no_group
                        ActionPlan.Problem.NO_WEBHOOK_URL -> R.string.te_problem_no_webhook
                        ActionPlan.Problem.NO_TRANSCRIBER -> R.string.te_problem_no_transcriber
                    },
                )
            }
            row.actionRowProblem.beVisibleIf(problems.isNotEmpty())
            row.root.setOnClickListener { menu(it, a.id) }
            binding.actionsList.addView(row.root)
        }
        updateTextColors(binding.actionsScrollview)
        binding.actionsAdd.setTextColor(getProperPrimaryColor())
        ActionTileService.refreshAll(this)
    }

    private fun menu(anchor: View, id: String) {
        val action = store.get(id) ?: return
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, R.string.te_edit_action)
            menu.add(0, 2, 1, R.string.te_action_move_up)
            menu.add(0, 3, 2, R.string.te_action_move_down)
            menu.add(0, 4, 3, org.fossify.commons.R.string.delete)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> ActionEditDialog(this@ActionsActivity, action) {
                        store.upsert(it)
                        refresh()
                    }

                    2 -> store.move(id, -1).also { refresh() }
                    3 -> store.move(id, 1).also { refresh() }
                    4 -> if (store.stored().orEmpty().size <= 1) {
                        toast(R.string.te_at_least_one_action)
                    } else {
                        ConfirmationDialog(this@ActionsActivity, getString(R.string.te_action_delete_confirm, action.name)) {
                            store.delete(id)
                            refresh()
                        }
                    }
                }
                true
            }
            show()
        }
    }
}
