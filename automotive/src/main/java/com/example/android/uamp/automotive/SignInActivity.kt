/*
 * Copyright 2019 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.uamp.automotive

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider

class SignInActivity : AppCompatActivity() {

    private lateinit var viewModel: SignInActivityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sign_in)

        viewModel = ViewModelProvider(this)
            .get(SignInActivityViewModel::class.java)

        viewModel.loggedIn.observe(this, Observer { loggedIn ->
            if (loggedIn == true) {
                Toast.makeText(this, R.string.sign_in_success_message, Toast.LENGTH_SHORT).show()
                finish()
            }
        })

        viewModel.missingFields.observe(this, Observer { missing ->
            if (missing) {
                Toast.makeText(this, R.string.missing_fields_error, Toast.LENGTH_SHORT).show()
            }
        })

        supportFragmentManager.beginTransaction()
            .add(R.id.sign_in_container, SignInLandingPageFragment())
            .commit()
    }
}