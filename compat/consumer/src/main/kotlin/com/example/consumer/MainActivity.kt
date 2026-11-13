package com.example.consumer

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Greeter @Inject constructor() {
    fun hi() = "hi"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var greeter: Greeter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        greeter.hi()
    }
}
