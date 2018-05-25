/*
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.activity

import android.content.Context
import android.content.Intent
import android.widget.CheckBox
import android.widget.EditText
import butterknife.BindView
import butterknife.OnClick
import com.google.gson.JsonObject
import im.vector.Matrix
import im.vector.R
import org.matrix.androidsdk.MXSession
import org.matrix.androidsdk.rest.callback.SimpleApiCallback
import org.matrix.androidsdk.rest.model.MatrixError
import java.lang.Exception

/**
 * Displays the Account deactivation screen.
 */
class DeactivateAccountActivity : RiotAppCompatActivity() {

    /* ==========================================================================================
     * UI
     * ========================================================================================== */

    @BindView(R.id.deactivate_account_erase_checkbox)
    lateinit var eraseCheckBox: CheckBox

    @BindView(R.id.deactivate_account_password)
    lateinit var passwordEditText: EditText

    /* ==========================================================================================
     * DATA
     * ========================================================================================== */

    private lateinit var session: MXSession

    /* ==========================================================================================
     * Life cycle
     * ========================================================================================== */

    override fun getLayoutRes() = R.layout.activity_deactivate_account

    override fun initUiAndData() {
        super.initUiAndData()

        configureToolbar()

        waitingView = findViewById(R.id.waiting_view)

        // Get the session
        session = Matrix.getInstance(this).defaultSession
    }

    override fun getTitleRes() = R.string.deactivate_account_title

    /* ==========================================================================================
     * UI Event
     * ========================================================================================== */

    @OnClick(R.id.deactivate_account_button_submit)
    internal fun onSubmit() {
        // Validate field
        val password = passwordEditText.text.toString()

        if (password.isEmpty()) {
            passwordEditText.error = getString(R.string.auth_missing_password)
            return
        }

        showWaitingView()

        session.profileApiClient.deactivateAccount(session.myUserId,
                password,
                eraseCheckBox.isChecked,
                object : SimpleApiCallback<JsonObject>(this) {
                    override fun onSuccess(info: JsonObject) {
                        hideWaitingView()

                        CommonActivityUtils.logout(this@DeactivateAccountActivity, true, false)
                    }

                    override fun onMatrixError(e: MatrixError) {
                        if (e.errcode == MatrixError.FORBIDDEN) {
                            passwordEditText.error = getString(R.string.auth_invalid_login_param)
                        } else {
                            super.onMatrixError(e)
                        }

                        hideWaitingView()
                    }

                    override fun onNetworkError(e: Exception) {
                        super.onNetworkError(e)

                        hideWaitingView()
                    }

                    override fun onUnexpectedError(e: Exception) {
                        super.onUnexpectedError(e)

                        hideWaitingView()
                    }
                }
        )
    }

    @OnClick(R.id.deactivate_account_button_cancel)
    internal fun onCancel() {
        finish()
    }

    /* ==========================================================================================
     * Companion
     * ========================================================================================== */

    companion object {
        fun getIntent(context: Context) = Intent(context, DeactivateAccountActivity::class.java)
    }
}
