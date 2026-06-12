package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Task
import com.example.ui.TimetableViewModel
import com.example.ui.TimetableViewModelFactory
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.Translations
import android.net.Uri
import android.content.Intent
import java.util.Calendar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val viewModel: TimetableViewModel by viewModels { TimetableViewModelFactory(context.applicationContext as android.app.Application) }
                
                val initialDone by viewModel.isInitialDownloadDone.collectAsStateWithLifecycle()
                var currentScreen by remember { mutableStateOf("SPLASH") }
                
                val hasPermission = remember {
                    mutableStateOf(
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.ACCESS_FINE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                        androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                }

                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                            val granted = (
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.ACCESS_FINE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                                androidx.core.content.ContextCompat.checkSelfPermission(
                                    context,
                                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            )
                            hasPermission.value = granted
                            if (granted && currentScreen == "SETUP_GATE") {
                                currentScreen = "MAIN"
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
                    val coarseGranted = permissions[android.Manifest.permission.ACCESS_COARSE_LOCATION] ?: false
                    hasPermission.value = fineGranted || coarseGranted
                    if (fineGranted || coarseGranted) {
                        viewModel.fetchLocationAndInitializeTimes(this@MainActivity)
                        if (currentScreen == "SETUP_GATE") {
                            currentScreen = "MAIN"
                        }
                    }
                }

                val targetScreen = remember(currentScreen, hasPermission.value, initialDone) {
                    if (currentScreen == "SPLASH") {
                        "SPLASH"
                    } else if (!initialDone && !hasPermission.value) {
                        "SETUP_GATE"
                    } else if (currentScreen == "SETUP_GATE" && hasPermission.value) {
                        "MAIN"
                    } else {
                        currentScreen
                    }
                }
                
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black // Pure black theme canvas 
                ) {
                    AnimatedContent(
                        targetState = targetScreen,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(400)) togetherWith fadeOut(animationSpec = tween(400))
                        },
                        label = "MainScreenFlow"
                    ) { screen ->
                        when (screen) {
                            "SPLASH" -> SplashScreen {
                                viewModel.scheduleDailyAlarms(this@MainActivity)
                                if (initialDone) {
                                    currentScreen = "MAIN"
                                } else if (hasPermission.value) {
                                    currentScreen = "MAIN"
                                } else {
                                    currentScreen = "SETUP_GATE"
                                }
                            }
                            "SETUP_GATE" -> SetupGateScreen(
                                onEnableClicked = {
                                    locationPermissionLauncher.launch(
                                        arrayOf(
                                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                                        )
                                    )
                                }
                            )
                            "MAIN" -> {
                                LaunchedEffect(initialDone) {
                                    if (!initialDone) {
                                        viewModel.fetchLocationAndInitializeTimes(this@MainActivity)
                                    }
                                }
                                MainScreen(
                                    viewModel = viewModel,
                                    onNavigateToSounds = { currentScreen = "SOUND_SETTINGS" },
                                    onRequestLocationTrigger = {
                                        locationPermissionLauncher.launch(
                                            arrayOf(
                                                android.Manifest.permission.ACCESS_FINE_LOCATION,
                                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                                            )
                                        )
                                    }
                                )
                            }
                            "SOUND_SETTINGS" -> SoundAndNotificationScreen(
                                viewModel = viewModel,
                                onBack = { currentScreen = "MAIN" }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SetupGateScreen(onEnableClicked: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111111)),
            border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.2f)),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .background(Color(0xFF00FFCC).copy(alpha = 0.1f), CircleShape)
                        .border(1.5.dp, Color(0xFF00FFCC).copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Location Access Required",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Text(
                    text = "Location Required",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "To proceed, please ensure your Internet and GPS/Location services are turned on. If the data fails to load, please ensure that Location permissions are granted to the app in your device settings. If you still encounter issues, please go to the App Settings and tap 'Re-sync Data' to refresh your location and schedule.",
                    color = Color.White.copy(alpha = 0.65f),
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onEnableClicked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FFCC),
                        contentColor = Color.Black
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("enable_location_permission_button"),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = "OK",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

// 1. PREMIUM SPLASH SCREEN (VHGPL TIMEFLOW ARCHITECTURE)
@Composable
fun SplashScreen(onSplashFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2500) // Precise 2.5s display delay
        onSplashFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "brandPulse")
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.93f,
                targetValue = 1.05f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1200, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scalePulse"
            )

            Text(
                text = "VHGPL",
                fontSize = 62.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
                letterSpacing = 8.sp,
                fontFamily = FontFamily.Serif,
                modifier = Modifier.graphicsLayer(scaleX = scale, scaleY = scale)
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = "TECHNOLOGY & AI",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 5.sp,
                fontFamily = FontFamily.SansSerif
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "TIMEFLOW SYSTEM",
                fontSize = 11.sp,
                fontWeight = FontWeight.Light,
                color = Color.Yellow,
                letterSpacing = 2.sp
            )
        }
    }
}

// 2. MAIN HUB CONTROLLER (DUAL LANGUAGE ALIGNMENT)
@Composable
fun MainScreen(
    viewModel: TimetableViewModel,
    onNavigateToSounds: () -> Unit,
    onRequestLocationTrigger: () -> Unit
) {
    val context = LocalContext.current
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val currMinutes by viewModel.currentMinutes.collectAsStateWithLifecycle()
    val isSimulated by viewModel.isSimulatedTime.collectAsStateWithLifecycle()
    val isUrdu by viewModel.isUrduLoyal.collectAsStateWithLifecycle()
    val selectedDate by viewModel.selectedDate.collectAsStateWithLifecycle()
    val monthlyPercent by viewModel.monthlyPerformancePercent.collectAsStateWithLifecycle()
    val monthlyTitle by viewModel.monthlyTitle.collectAsStateWithLifecycle()
    val showMonthlyReport by viewModel.showMonthlyReport.collectAsStateWithLifecycle()
    val totalTrackedDays by viewModel.totalTrackedDays.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()

    var showAddTaskDialog by remember { mutableStateOf(false) }
    var prayerToEdit by remember { mutableStateOf<Task?>(null) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showDetailsTask by remember { mutableStateOf<Task?>(null) }

    val todayDateStr = viewModel.getTodayDateString()
    val isTodayDisplayed = (selectedDate == todayDateStr)
    val isFrozen = viewModel.isDayFrozen(tasks, selectedDate, currMinutes)

    // Set correct layout direction automatically
    val layoutDir = if (isUrdu) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
        Scaffold(
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = Translations.get("app_title", currentLanguage),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Text(
                                text = Translations.get("version_text", currentLanguage),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        }

                        // Persistent Top Bar Settings Gear Button
                        IconButton(
                            onClick = { showSettingsDialog = true },
                            modifier = Modifier
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                                .size(36.dp)
                                .testTag("settings_gear_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            },
            bottomBar = {},
            containerColor = Color.Black
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {

                // 1. TOP PREMIUM REAL-TIME HUD WITH LIVE PERFORMANCES
                HUDCard(viewModel, tasks, currMinutes, isUrdu, selectedDate, todayDateStr)

                // 2. 40-DAY MONTHLY ARCHIEVEMENT ASSIGNER HUD (SECURE COMPLIANCE)
                MonthlyTitleCard(
                    percent = monthlyPercent,
                    title = monthlyTitle,
                    isUrdu = isUrdu,
                    showReport = showMonthlyReport,
                    trackedDays = totalTrackedDays
                )

                // 3. DATE BROWSER NAVIGATION BAR (LOCKED PAST HISTORY)
                DateBrowserBar(viewModel, selectedDate, currentLanguage, todayDateStr)

                // Freeze Indicator Alert Label (Exactly 5 mins post final task)
                if (isFrozen) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2B0B0C)),
                        border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = "Day Frozen", tint = Color.Red)
                            Column {
                                Text(
                                    text = Translations.get("day_frozen_title", currentLanguage),
                                    color = Color.Red,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = Translations.get("day_frozen_desc", currentLanguage),
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                val totalToday = tasks.size
                                val completedToday = tasks.count { it.isCompleted }
                                val dailyPercent = if (totalToday > 0) (completedToday * 100) / totalToday else 0
                                Text(
                                    text = if (isUrdu) "آج کا تعمیلی اسکور: $dailyPercent%" else "Daily Compliance Score: $dailyPercent%",
                                    color = Color(0xFF00FFCC),
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.testTag("daily_compliance_percentage")
                                )
                            }
                        }
                    }
                }

                // 4. HEADER CONTROL BUTTONS
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Translations.get("schedule_timetable", currentLanguage),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    // Add custom task & clean triggers (disabled if frozen or past day)
                    if (!isFrozen) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { 
                                    viewModel.initializeDefaultTaskTimes()
                                    showAddTaskDialog = true 
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 11.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("add_custom_btn_trigger")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = Translations.get("add_task", currentLanguage),
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    } else {
                        // History badge
                        Box(
                            modifier = Modifier
                                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = Translations.get("immutable_record", currentLanguage),
                                color = Color.White.copy(alpha = 0.6f),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // 5. CHRONOLOGICAL DATA TABLE (ٹیبل فارمیٹ)
                InteractiveTimetableTable(
                    tasks = tasks,
                    viewModel = viewModel,
                    currMinutes = currMinutes,
                    currentLanguage = currentLanguage,
                    isFrozen = isFrozen,
                    isToday = isTodayDisplayed,
                    onEditPrayerClick = { prayerToEdit = it },
                    onTaskClick = { showDetailsTask = it }
                )

                Spacer(modifier = Modifier.height(20.dp))
            }
        }
    }

    // Task Creation Form dialog
    if (showAddTaskDialog) {
        AddTaskFormDialog(viewModel = viewModel, isUrduGlobal = isUrdu) {
            showAddTaskDialog = false
        }
    }

    // Settings Configuration Dialog
    if (showSettingsDialog) {
        SettingsDialog(
            viewModel = viewModel,
            onOpenSoundSettings = {
                showSettingsDialog = false
                onNavigateToSounds()
            },
            onRequestLocationTrigger = onRequestLocationTrigger,
            onDismiss = { showSettingsDialog = false }
        )
    }

    // Task Details Popup
    if (showDetailsTask != null) {
        TaskDetailDialog(
            task = showDetailsTask!!,
            viewModel = viewModel,
            currentLanguage = currentLanguage
        ) {
            showDetailsTask = null
        }
    }

    // Prayer alarm adjustments Dialog
    prayerToEdit?.let { task ->
        EditPrayerTimesDialog(
            task = task,
            isUrdu = isUrdu,
            onDismiss = { prayerToEdit = null },
            onSave = { newStart, newEnd ->
                viewModel.editPrayerTimes(task, newStart, newEnd, context)
                prayerToEdit = null
            }
        )
    }
}

// 3. TOP REAL-TIME COMPLIANCE HUD
@Composable
fun HUDCard(
    viewModel: TimetableViewModel,
    tasks: List<Task>,
    currMinutes: Int,
    isUrdu: Boolean,
    selectedDate: String,
    todayDateStr: String
) {
    val tickingTime by viewModel.realTimeClockStr.collectAsStateWithLifecycle()
    val isSimulated by viewModel.isSimulatedTime.collectAsStateWithLifecycle()

    val displayClock = if (isSimulated) {
        val hrs = currMinutes / 60
        val mins = currMinutes % 60
        String.format("%02d:%02d [Sim]", hrs, mins)
    } else {
        tickingTime
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("hud_card_container"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Clock/Time Info Block
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Time",
                            tint = Color(0xFFE5C05B),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isUrdu) "حقیقی وقت" else "Current Time",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = displayClock,
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("hud_realtime_clock")
                    )
                }

                // Calendar / Date / Weekday Info Block
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = selectedDate,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val dayText = viewModel.getTodayDayOfWeek(selectedDate)
                    val dayUrdu = when (dayText.lowercase()) {
                        "monday" -> "پیر"
                        "tuesday" -> "منگل"
                        "wednesday" -> "بدھ"
                        "thursday" -> "جمعرات"
                        "friday" -> "جمعہ"
                        "saturday" -> "ہفتہ"
                        "sunday" -> "اتوار"
                        else -> "آج"
                    }
                    Text(
                        text = if (isUrdu) "$dayUrdu ($dayText)" else dayText,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            val locationDisplay by viewModel.resolvedLocationDisplay.collectAsStateWithLifecycle()
            
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.08f),
                modifier = Modifier.padding(vertical = 10.dp)
            )
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().testTag("hud_location_display_row")
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Active Location",
                    tint = Color(0xFF00FFCC),
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (isUrdu) "مقام: $locationDisplay" else "Location: $locationDisplay",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            val realTimeSec = remember(tickingTime) {
                val c = java.util.Calendar.getInstance()
                c.get(java.util.Calendar.HOUR_OF_DAY) * 3600 + c.get(java.util.Calendar.MINUTE) * 60 + c.get(java.util.Calendar.SECOND)
            }

            var showWarningBox = false
            var warningText = ""

            val activeWarningTask = remember(realTimeSec, tasks) {
                tasks.firstOrNull { task ->
                    val startSec = viewModel.timeToMinutes(task.startTime) * 60
                    val endSec = viewModel.timeToMinutes(task.endTime) * 60
                    realTimeSec in startSec..endSec && !task.isCompleted && !task.isFailed
                }
            }

            if (activeWarningTask != null) {
                val endSec = viewModel.timeToMinutes(activeWarningTask.endTime) * 60
                val remainingSec = endSec - realTimeSec
                if (remainingSec in 1..300) {
                    showWarningBox = true
                    val name = if (isUrdu && activeWarningTask.nameUrdu.isNotBlank()) activeWarningTask.nameUrdu else activeWarningTask.nameEnglish
                    val mins = remainingSec / 60
                    val secs = remainingSec % 60
                    val remainingFormatted = String.format("%02d:%02d", mins, secs)
                    warningText = if (isUrdu) "انتباہ! ٹاسک کلوژر: $name ($remainingFormatted)" else "WARNING! Task ending soon: $name ($remainingFormatted)"
                }
            }

            if (showWarningBox) {
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFD32F2F), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White, RoundedCornerShape(8.dp))
                        .padding(10.dp)
                        .testTag("in_app_warning_red_box")
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = warningText,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("in_app_task_countdown_text")
                    )
                }
            }
        }
    }
}

// 15-Level Progressive Badge System (0-100%)
data class BadgeLevel(
    val level: Int,
    val name: String,
    val urduName: String,
    val color: Color,
    val isPremium: Boolean,
    val rangeStr: String
)

fun getBadgeForPercent(percent: Int): BadgeLevel {
    return when {
        percent <= 0 -> BadgeLevel(0, "Initiate (L0)", "مبتدی", Color(0xFF9E9E9E), false, "0%")
        percent in 1..15 -> BadgeLevel(1, "Bronze Explorer (L1)", "کانسی مہم جو", Color(0xFFCD7F32), false, "1-15%")
        percent in 16..29 -> BadgeLevel(2, "Silver Tracker (L2)", "چاندی کھوجی", Color(0xFFC0C0C0), false, "16-29%")
        percent in 30..40 -> BadgeLevel(3, "Gold Architect (L3)", "سونا معمار", Color(0xFFFFD700), false, "30-40%")
        percent in 41..47 -> BadgeLevel(4, "Platinum Specialist (L4)", "پلاٹینم ماہر", Color(0xFFE5E4E2), false, "41-47%")
        percent in 48..54 -> BadgeLevel(5, "Iron Sentinel (L5)", "لوہا محافظ", Color(0xFF8A9A86), false, "48-54%")
        percent in 55..61 -> BadgeLevel(6, "Steel Vanguard (L6)", "فولاد دستہ", Color(0xFF4682B4), false, "55-61%")
        percent in 62..68 -> BadgeLevel(7, "Obsidian Seeker (L7)", "اوبسیڈین طالب", Color(0xFF2F4F4F), false, "62-68%")
        percent in 69..75 -> BadgeLevel(8, "Ruby Challenger (L8)", "یاقوت حریف", Color(0xFFDC143C), false, "69-75%")
        percent in 76..82 -> BadgeLevel(9, "Emerald Guardian (L9)", "زمرد امین", Color(0xFF50C878), false, "76-82%")
        percent in 83..89 -> BadgeLevel(10, "Titanium Commander (L10)", "ٹائٹینیئم سالار", Color(0xFF708090), false, "83-89%")
        
        // Premium Levels (11 - 15)
        percent in 90..91 -> BadgeLevel(11, "Chrono Master (L11) 👑", "وقت کا مائی مائی", Color(0xFF00E5FF), true, "90-91%")
        percent in 92..93 -> BadgeLevel(12, "Cosmic Champion (L12) 🌌", "کائناتی غازی", Color(0xFFD500F9), true, "92-93%")
        percent in 94..94 -> BadgeLevel(13, "Celestial Oracle (L13) ✨", "نوری پیشوا", Color(0xFFFF1744), true, "94%")
        percent in 95..99 -> BadgeLevel(14, "Divine Sovereign (L14) ⚜️", "شاہی حکمران", Color(0xFFFFEB3B), true, "95-99%")
        percent >= 100 -> BadgeLevel(15, "Ultimate Time Deity (L15) 🛡️⚡", "وقت کا لازوال دیوتا", Color(0xFFFF3D00), true, "100%")
        else -> BadgeLevel(0, "Initiate (L0)", "مبتدی", Color(0xFF9E9E9E), false, "0%")
    }
}

// 4. 40-DAY MONTHLY ACHIEVEMENT BADGE CARD (v1.5)
@Composable
fun MonthlyTitleCard(percent: Int, title: String, isUrdu: Boolean, showReport: Boolean, trackedDays: Int) {
    val badge = getBadgeForPercent(percent)
    val unlocked = trackedDays >= 40 // Hard-coded 40-day refresh cycle

    Card(
        modifier = Modifier.fillMaxWidth().testTag("monthly_performance_card"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
        border = BorderStroke(
            1.dp, 
            if (unlocked && badge.isPremium) {
                androidx.compose.ui.graphics.Brush.sweepGradient(listOf(badge.color, Color.Yellow, badge.color))
            } else {
                androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.White.copy(0.12f), Color.White.copy(0.04f)))
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (unlocked) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "Unlocked Badge",
                        tint = badge.color,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUrdu) {
                            "اسکور: $percent% • اعزاز: ${badge.urduName}"
                        } else {
                            "Performance: $percent% • Title: ${badge.name}"
                        },
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                val remainingDays = maxOf(1, 40 - trackedDays)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "New Student Status",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isUrdu) "نیا طالب علم" else "New Student",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

// 5. DATE SYSTEM BROWSING SELECTOR (LOCKED PAST RECORD)
@Composable
fun DateBrowserBar(viewModel: TimetableViewModel, selectedStr: String, currentLanguage: String, todayStr: String) {
    val loggedDates by viewModel.loggedDates.collectAsStateWithLifecycle()
    val hasPrevious = loggedDates.any { it < selectedStr }
    val hasNext = selectedStr < todayStr

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { viewModel.changeDateOffset(-1) },
                enabled = hasPrevious
            ) {
                val icon = if (currentLanguage == "Urdu") Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowLeft
                Icon(
                    icon,
                    contentDescription = "Previous Day",
                    tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.2f)
                )
            }

            // Centered Date label status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = selectedStr,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                if (selectedStr == todayStr) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color.White, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = Translations.get("today", currentLanguage),
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }
            }

            // Enable right offset click only if not past current day
            IconButton(
                onClick = { viewModel.changeDateOffset(1) },
                enabled = hasNext
            ) {
                val icon = if (currentLanguage == "Urdu") Icons.Default.KeyboardArrowLeft else Icons.Default.KeyboardArrowRight
                Icon(
                    icon,
                    contentDescription = "Next Day",
                    tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}

// 6. MAIN CHRONOLOGICAL HIGH CONTRAST COMPLIANCE GRID TABLE
@Composable
fun InteractiveTimetableTable(
    tasks: List<Task>,
    viewModel: TimetableViewModel,
    currMinutes: Int,
    currentLanguage: String,
    isFrozen: Boolean,
    isToday: Boolean,
    onEditPrayerClick: (Task) -> Unit,
    onTaskClick: (Task) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // TABLE HEADERS ROW
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(vertical = 12.dp, horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (currentLanguage == "Urdu") "ٹاسک کا نام" else "Task Name",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1.3f)
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Color.LightGray.copy(alpha = 0.2f))
                )

                Text(
                    text = if (currentLanguage == "Urdu") "شروع وقت" else "Start Time",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Color.LightGray.copy(alpha = 0.2f))
                )

                Text(
                    text = if (currentLanguage == "Urdu") "ختم وقت" else "End Time",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(0.8f),
                    textAlign = TextAlign.Center
                )

                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(18.dp)
                        .background(Color.LightGray.copy(alpha = 0.2f))
                )

                Text(
                    text = if (currentLanguage == "Urdu") "تکمیل / عمل" else "Status / Action",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1.2f),
                    textAlign = TextAlign.Center
                )
            }

            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            if (tasks.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Schedule empty for this date.",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            } else {
                tasks.forEachIndexed { i, task ->
                    TableCellRepresenter(
                        task = task,
                        viewModel = viewModel,
                        currMinutes = currMinutes,
                        currentLanguage = currentLanguage,
                        isFrozen = isFrozen,
                        isToday = isToday,
                        onEditPrayerClick = onEditPrayerClick,
                        onTaskClick = onTaskClick
                    )
                    if (i < tasks.lastIndex) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                    }
                }
            }
        }
    }
}

@Composable
fun BadgeLabel(text: String, bg: Color, fg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = fg,
                modifier = Modifier.size(9.dp)
            )
            Text(
                text = text,
                color = fg,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ScrollingWheelPicker(
    value: String,
    options: List<String>,
    onValueSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    val selectedIndex = remember(value, options) {
        val idx = options.indexOf(value)
        if (idx >= 0) idx else 0
    }
    
    val paddedOptions = remember(options) {
        listOf("") + options + listOf("")
    }
    
    LaunchedEffect(selectedIndex) {
        listState.scrollToItem(selectedIndex)
    }
    
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            if (visibleItems.isNotEmpty()) {
                val centerIndex = listState.firstVisibleItemIndex
                val finalIndex = centerIndex.coerceIn(0, options.lastIndex)
                onValueSelected(options[finalIndex])
            }
        }
    }
    
    Box(
        modifier = modifier
            .height(115.dp)
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(12.dp))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color.White.copy(alpha = 0.08f))
                .border(1.dp, Color(0xFF00FFCC).copy(alpha = 0.3f), RoundedCornerShape(4.dp))
        )
        
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            items(paddedOptions.size) { index ->
                val label = paddedOptions[index]
                val isCenter = (index == selectedIndex + 1)
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(34.dp)
                        .clickable(enabled = label.isNotEmpty()) {
                            coroutineScope.launch {
                                listState.animateScrollToItem(index - 1)
                                onValueSelected(label)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        color = if (isCenter) Color(0xFF00FFCC) else Color.White.copy(alpha = 0.4f),
                        fontSize = if (isCenter) 16.sp else 13.sp,
                        fontWeight = if (isCenter) FontWeight.Bold else FontWeight.Normal,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
fun TimeSelectionRow(
    label: String,
    timeValue: String, // format "HH:mm" or "HH:mm:ss"
    onTimeChanged: (String) -> Unit
) {
    val parts = remember(timeValue) {
        val clean = timeValue.trim().uppercase().replace("AM", "").replace("PM", "").trim()
        val splitParts = clean.split(":")
        val h = splitParts.getOrNull(0)?.toIntOrNull() ?: 9
        val m = splitParts.getOrNull(1)?.toIntOrNull() ?: 0
        Pair(
            String.format("%02d", h.coerceIn(0, 23)),
            String.format("%02d", m.coerceIn(0, 59))
        )
    }

    val (hourStr, minStr) = parts

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hours scroll picker
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hour",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)
                )
                ScrollingWheelPicker(
                    value = hourStr,
                    options = (0..23).map { String.format("%02d", it) },
                    onValueSelected = { newHour ->
                        onTimeChanged("$newHour:$minStr")
                    }
                )
            }

            // Minutes scroll picker
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Minute",
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp)
                )
                ScrollingWheelPicker(
                    value = minStr,
                    options = (0..59).map { String.format("%02d", it) },
                    onValueSelected = { newMin ->
                        onTimeChanged("$hourStr:$newMin")
                    }
                )
            }
        }
    }
}

@Composable
fun TimeDropdownField(
    label: String,
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .clickable { expanded = true }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Column {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(Color(0xFF1E1E1E))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                .heightIn(max = 240.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp
                        )
                    },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun TableCellRepresenter(
    task: Task,
    viewModel: TimetableViewModel,
    currMinutes: Int,
    currentLanguage: String,
    isFrozen: Boolean,
    isToday: Boolean,
    onEditPrayerClick: (Task) -> Unit,
    onTaskClick: (Task) -> Unit
) {
    val context = LocalContext.current
    val submittable = viewModel.isTaskSubmittable(task, currMinutes)
    val taskDisplayName = if (currentLanguage == "Urdu") {
        if (task.nameUrdu.isNotBlank()) task.nameUrdu else task.nameEnglish
    } else {
        if (task.nameEnglish.isNotBlank()) task.nameEnglish else task.nameUrdu
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (task.isFailed) Color.Red.copy(alpha = 0.04f) else Color.Transparent)
            .clickable { onTaskClick(task) }
            .padding(vertical = 12.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Col 1: Task Details (Name and Badge labels)
        Column(modifier = Modifier.weight(1.3f)) {
            Text(
                text = taskDisplayName,
                color = if (task.isFailed) Color(0xFFF35B04) else Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )

        // Col 2: Start Time
        Column(
            modifier = Modifier.weight(0.8f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = task.startTime,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )

        // Col 3: End Time
        Column(
            modifier = Modifier.weight(0.8f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = task.endTime,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(Color.White.copy(alpha = 0.12f))
        )

        // Col 4: Status / Submit action
        Box(
            modifier = Modifier.weight(1.2f),
            contentAlignment = Alignment.Center
        ) {
            when {
                task.isCompleted -> {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = Color(0xFF56E39F),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = Translations.get("completed", currentLanguage),
                            color = Color(0xFF56E39F),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                task.isFailed -> {
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Failed",
                            tint = Color(0xFFF35B04),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = Translations.get("failed", currentLanguage),
                            color = Color(0xFFF35B04),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                else -> {
                    if (submittable) {
                        Button(
                            onClick = { viewModel.toggleTaskCompletion(task) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(6.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                            modifier = Modifier.height(26.dp)
                        ) {
                            Text(
                                text = if (currentLanguage == "Urdu") "جمع" else "Submit",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "Upcoming",
                                tint = Color.White.copy(alpha = 0.25f),
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = if (currentLanguage == "Urdu") "منتظر" else "Pending",
                                color = Color.White.copy(alpha = 0.25f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// 7. TASK CREATOR FORM DIALOG (ENG / URDU HYBRID POPUP)
@Composable
fun AddTaskFormDialog(
    viewModel: TimetableViewModel,
    isUrduGlobal: Boolean,
    onDismiss: () -> Unit
) {
    val name by viewModel.taskNameInput.collectAsStateWithLifecycle()
    val start by viewModel.startTimeInput.collectAsStateWithLifecycle()
    val end by viewModel.endTimeInput.collectAsStateWithLifecycle()
    val isPermanent by viewModel.isTaskInputPermanent.collectAsStateWithLifecycle()
    val isImportant by viewModel.isTaskImportantInput.collectAsStateWithLifecycle()
    val isUrduInput by viewModel.isDialogUrduMode.collectAsStateWithLifecycle()
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val selectedWeekdaysList by viewModel.selectedWeekDays.collectAsStateWithLifecycle()
    val isTaskLockedInput by viewModel.isTaskLockedInput.collectAsStateWithLifecycle()

    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = Translations.get("new_task", currentLanguage),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                // Language switch selector tool directly above input area
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Translations.get("input_lang", currentLanguage),
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp
                    )

                    Row {
                        Button(
                            onClick = { viewModel.isDialogUrduMode.value = false },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (!isUrduInput) Color.White else Color.Transparent
                            ),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "ENG",
                                color = if (!isUrduInput) Color.Black else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Button(
                            onClick = { viewModel.isDialogUrduMode.value = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isUrduInput) Color.White else Color.Transparent
                            ),
                            shape = RoundedCornerShape(4.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                "اردو",
                                color = if (isUrduInput) Color.Black else Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // Name TextField with adaptive labels
                OutlinedTextField(
                    value = name,
                    onValueChange = { viewModel.updateTaskName(it) },
                    label = { 
                        Text(
                            text = Translations.get("task_name_hint", currentLanguage),
                            color = Color.White.copy(alpha = 0.6f)
                        ) 
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("add_dialog_name_input")
                )

                // Starting hours (HH:mm)
                TimeSelectionRow(
                    label = Translations.get("start_time_hint", currentLanguage),
                    timeValue = start,
                    onTimeChanged = { viewModel.updateStartTime(it) }
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Ending hours (HH:mm)
                TimeSelectionRow(
                    label = Translations.get("end_time_hint", currentLanguage),
                    timeValue = end,
                    onTimeChanged = { viewModel.updateEndTime(it) }
                )

                // Permanent checklist lock selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPermanent,
                        onCheckedChange = { viewModel.togglePermanentCheckbox() },
                        colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = Translations.get("repeating_task", currentLanguage),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Important Checklist priority selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isImportant,
                        onCheckedChange = { viewModel.isTaskImportantInput.value = it },
                        colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFB300), checkmarkColor = Color.Black),
                        modifier = Modifier.testTag("priority_checkbox")
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (currentLanguage == "Urdu") "خصوصی اہم ٹاسک کے طور پر نشان زد کریں" else "Mark as Important / Priority Task",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                // D. CHIPS SELECTOR ROW
                Text(
                    text = Translations.get("target_weekdays", currentLanguage),
                    color = Color.White.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                val daysKeys = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    daysKeys.forEachIndexed { idx, key ->
                        val isSelected = selectedWeekdaysList[idx]
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(
                                    color = if (isSelected) Color.White else Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { viewModel.toggleWeekdaySelection(idx) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = Translations.get(key, currentLanguage),
                                color = if (isSelected) Color.Black else Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }
                    }
                }



                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                // Bottom actions buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            viewModel.addNewTask(context)
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("dialog_save_btn")
                    ) {
                        Text(
                            text = Translations.get("save_task", currentLanguage),
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = Translations.get("cancel", currentLanguage),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

// 8. MANDATORY PRAYERS TIME UPDATE DIALOG
@Composable
fun EditPrayerTimesDialog(
    task: Task,
    isUrdu: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var editStart by remember { mutableStateOf(task.startTime) }
    var editEnd by remember { mutableStateOf(task.endTime) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                val displayName = if (isUrdu && task.nameUrdu.isNotBlank()) task.nameUrdu else task.nameEnglish
                
                Text(
                    text = if (isUrdu) "وقت درست کریں: $displayName" else "Modify Times: $displayName",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                // Start
                OutlinedTextField(
                    value = editStart,
                    onValueChange = { editStart = it },
                    label = { Text("شروع کا وقت (HH:mm format)", color = Color.White.copy(alpha = 0.6f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("prayer_edit_start")
                )

                // End
                OutlinedTextField(
                    value = editEnd,
                    onValueChange = { editEnd = it },
                    label = { Text("ختم ہونے کا وقت (HH:mm format)", color = Color.White.copy(alpha = 0.6f)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.White,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                    ),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("prayer_edit_end")
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSave(editStart, editEnd) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f).testTag("prayer_save_btn")
                    ) {
                        Text(
                            text = if (isUrdu) "محفوظ کریں" else "Save Changes",
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isUrdu) "منسوخ" else "Cancel",
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(
    viewModel: TimetableViewModel,
    onOpenSoundSettings: () -> Unit,
    onRequestLocationTrigger: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentLanguage by viewModel.currentLanguage.collectAsStateWithLifecycle()
    val isMuslimMode by viewModel.isMuslimMode.collectAsStateWithLifecycle()
    val hasSavedDataForCurrentMode by viewModel.hasSavedDataForCurrentMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isLangMenuExpanded by remember { mutableStateOf(false) }
    var isRegionLocked by remember { mutableStateOf(true) }
    var showSwitchConfirmDialog by remember { mutableStateOf(false) }
    var showHistoryLogsDialog by remember { mutableStateOf(false) }

    if (showHistoryLogsDialog) {
        HistoryLogsDialog(
            viewModel = viewModel,
            currentLanguage = currentLanguage,
            onDismiss = { showHistoryLogsDialog = false }
        )
    }

    if (showSwitchConfirmDialog) {
        val dialogTitle = if (currentLanguage == "Urdu") "معلومات محفوظ کریں یا مٹائیں؟" else "Keep or Delete Data?"
        val dialogMsg = if (currentLanguage == "Urdu") {
            "مذہبی موڈ تبدیل کرنے سے پہلے کیا آپ موجودہ موڈ کا ڈیٹا محفوظ رکھنا چاہتے ہیں یا اسے مکمل ختم کرنا چاہتے ہیں؟"
        } else {
            "Do you want to keep (backup) or permanently delete your current mode's data before switching?"
        }
        AlertDialog(
            onDismissRequest = { showSwitchConfirmDialog = false },
            title = { Text(dialogTitle, fontWeight = FontWeight.Bold, color = Color.White) },
            text = { Text(dialogMsg, color = Color.White.copy(alpha = 0.8f)) },
            containerColor = Color(0xFF1E1E1E),
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.switchReligiousMode(context, keepData = true)
                        showSwitchConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC))
                ) {
                    Text(if (currentLanguage == "Urdu") "محفوظ رکھیں" else "Keep Data", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Button(
                    onClick = {
                        viewModel.switchReligiousMode(context, keepData = false)
                        showSwitchConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5252))
                ) {
                    Text(if (currentLanguage == "Urdu") "ڈیٹا مٹائیں" else "Delete Data", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = Translations.get("settings_title", currentLanguage),
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                // A. RELIGIOUS ROUTINE MODE TOGGLE
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = Translations.get("religious_mode", currentLanguage),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (isRegionLocked) {
                                    if (currentLanguage == "Urdu") "تبدیل کرنے کے لیے اوپر ریجن لاک کھولیں" else "🔒 Region Locked (Unlock above to switch)"
                                } else {
                                    if (isMuslimMode) "Muslim Routine Enabled" else "Non-Muslim Routine Enabled"
                                },
                                color = if (isRegionLocked) Color(0xFFFF5252) else Color.White.copy(alpha = 0.5f),
                                fontSize = 11.sp,
                                fontWeight = if (isRegionLocked) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                        Switch(
                            checked = isMuslimMode,
                            onCheckedChange = { showSwitchConfirmDialog = true },
                            enabled = !isRegionLocked,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Black,
                                checkedTrackColor = Color.White,
                                uncheckedThumbColor = Color.White,
                                uncheckedTrackColor = Color.White.copy(alpha = 0.15f),
                                disabledCheckedTrackColor = Color.White.copy(alpha = 0.1f),
                                disabledCheckedThumbColor = Color.Gray,
                                disabledUncheckedTrackColor = Color.White.copy(alpha = 0.05f),
                                disabledUncheckedThumbColor = Color.DarkGray
                            ),
                            modifier = Modifier.testTag("religious_routine_flag")
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                // B. RESTORE/LOAD DATA OPTION (Only appears if current mode has saved backups)
                if (hasSavedDataForCurrentMode) {
                    Text(
                        text = "Data Recovery Available",
                        color = Color(0xFF00FFCC),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Button(
                        onClick = { viewModel.loadSavedDataForCurrentMode(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().testTag("load_saved_data_btn")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Load Data", tint = Color.Black, modifier = Modifier.size(16.dp))
                            Text("Load Saved Data", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
                }

                // B2. LOCALIZED MULTI-LANGUAGE SYSTEM
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "System Language Selection",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )

                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { isLangMenuExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.1f)),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().testTag("language_selector_pill")
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = Translations.languageDisplayMap[currentLanguage] ?: "🇺🇸 English",
                                    color = Color.White,
                                    fontSize = 13.sp
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Expand",
                                    tint = Color.White
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = isLangMenuExpanded,
                            onDismissRequest = { isLangMenuExpanded = false },
                            modifier = Modifier
                                .fillMaxWidth(0.8f)
                                .heightIn(max = 280.dp)
                                .background(Color(0xFF161616))
                                .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                        ) {
                            Translations.languages.forEach { tempLang ->
                                val label = Translations.languageDisplayMap[tempLang] ?: tempLang
                                DropdownMenuItem(
                                    text = { Text(label, color = Color.White, fontSize = 13.sp) },
                                    onClick = {
                                        viewModel.setLanguage(tempLang)
                                        isLangMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                // C. LINK TO DEDICATED SOUND SCREEN
                Text(
                    text = "Alarms & Custom Sounds",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                
                Button(
                    onClick = onOpenSoundSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5C05B)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("open_sound_settings_btn")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Notifications, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Configure Sounds & Alerts", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                // D. LOCATION DYNAMIC SYNC TRIGGER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Dynamic Timing Auto-Sync",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    IconButton(
                        onClick = { isRegionLocked = !isRegionLocked },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (isRegionLocked) Icons.Default.Lock else Icons.Default.CheckCircle,
                            contentDescription = "Toggle Region Lock",
                            tint = if (isRegionLocked) Color(0xFFFF5252) else Color(0xFF00FFCC)
                        )
                    }
                }

                Button(
                    onClick = {
                        if (!isRegionLocked) {
                            val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.ACCESS_FINE_LOCATION
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                android.Manifest.permission.ACCESS_COARSE_LOCATION
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            
                            if (fineGranted || coarseGranted) {
                                onDismiss()
                                viewModel.triggerManualGpsReSync(context)
                            } else {
                                android.widget.Toast.makeText(context, "Location permission not granted. Requesting...", android.widget.Toast.LENGTH_SHORT).show()
                                onRequestLocationTrigger()
                            }
                        }
                    },
                    enabled = !isRegionLocked,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRegionLocked) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.15f),
                        disabledContainerColor = Color.White.copy(alpha = 0.05f)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("auto_location_sync_btn")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isRegionLocked) Icons.Default.Lock else Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (isRegionLocked) Color.White.copy(alpha = 0.3f) else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isRegionLocked) "Region Locked (Unlock to GPS Sync)" else "Sync Times with GPS Location",
                            color = if (isRegionLocked) Color.White.copy(alpha = 0.3f) else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                // E. HISTORY LOG REORGANIZATION - MOVE HISTORY TO SEPARATE BUTTON
                Text(
                    text = if (currentLanguage == "Urdu") "ہسٹری لاگز اور تعمیلی ریکارڈ" else "History Logs & Compliance",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )

                Button(
                    onClick = { showHistoryLogsDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("show_history_logs_btn")
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DateRange, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (currentLanguage == "Urdu") "ہسٹری لاگز دیکھیں" else "View History Logs",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close Settings", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun ChannelAudioRowWithPreview(
    label: String,
    uriVal: String,
    channelKey: String,
    viewModel: TimetableViewModel,
    onPickClick: () -> Unit,
    onResetClick: () -> Unit
) {
    val context = LocalContext.current
    val previewingKey by viewModel.isPreviewPlaying.collectAsStateWithLifecycle()
    val isPreviewingNow = (previewingKey == channelKey)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.04f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            if (uriVal.isNotBlank()) {
                Text(
                    text = "Reset Default",
                    color = Color(0xFFFF5252),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onResetClick() }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onPickClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.08f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = if (uriVal.isNotBlank()) uriVal.substringAfterLast("/") else "System Internal Default",
                        color = if (uriVal.isNotBlank()) Color.White else Color.White.copy(0.4f),
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = {
                    if (isPreviewingNow) {
                        viewModel.stopPreviewSound()
                    } else {
                        viewModel.playPreviewSound(context, uriVal, channelKey)
                    }
                },
                modifier = Modifier
                    .background(
                        if (isPreviewingNow) Color(0xFFE5C05B) else Color.White.copy(alpha = 0.08f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = if (isPreviewingNow) Icons.Default.Close else Icons.Default.PlayArrow,
                    contentDescription = if (isPreviewingNow) "Stop Preview" else "Play Preview",
                    tint = if (isPreviewingNow) Color.Black else Color.White,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
fun SoundAndNotificationScreen(
    viewModel: TimetableViewModel,
    onBack: () -> Unit
) {
    val isGlobalSoundEnabled by viewModel.isGlobalSoundEnabled.collectAsStateWithLifecycle()
    val appVolume by viewModel.appVolume.collectAsStateWithLifecycle()

    val alarmPermanentUri by viewModel.alarmPermanentUri.collectAsStateWithLifecycle()
    val alarmImportantUri by viewModel.alarmImportantUri.collectAsStateWithLifecycle()
    val alarmNormalUri by viewModel.alarmNormalUri.collectAsStateWithLifecycle()
    val notificationPermanentUri by viewModel.notificationPermanentUri.collectAsStateWithLifecycle()
    val notificationImportantUri by viewModel.notificationImportantUri.collectAsStateWithLifecycle()
    val notificationNormalUri by viewModel.notificationNormalUri.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var activePickingChannel by remember { mutableStateOf("") }

    val audioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(it, takeFlags)
            } catch (e: Exception) {}
            val uriStr = it.toString()
            when (activePickingChannel) {
                "alarm_permanent" -> viewModel.setAlarmPermanentUri(uriStr)
                "alarm_important" -> viewModel.setAlarmImportantUri(uriStr)
                "alarm_normal" -> viewModel.setAlarmNormalUri(uriStr)
                "notification_permanent" -> viewModel.setNotificationPermanentUri(uriStr)
                "notification_important" -> viewModel.setNotificationImportantUri(uriStr)
                "notification_normal" -> viewModel.setNotificationNormalUri(uriStr)
            }
        }
    }

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .size(36.dp)
                    ) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Sound & Notification",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        },
        containerColor = Color.Black
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Notification Hard-lock Indicator Banner (v2.0.0 compliance)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF0C1917)),
                border = BorderStroke(1.dp, Color(0xFF00FFCC).copy(alpha = 0.4f)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Hard-locked Alert State",
                        tint = Color(0xFF00FFCC),
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Tray-Only Delivery Hardcoded",
                            color = Color(0xFF00FFCC),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "All alerts are hard-locked to Silent, No Vibration, and No Heads-up pop-ups. Alerts render cleanly in your notification tray with dynamic countdowns for optimal mental focus.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }
            }

            // Global Toggle
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.05f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Global Audio Alerts", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(
                            text = if (isGlobalSoundEnabled) "Mute system completely by flipping the master switch" else "Sound alerts are fully muted. Click to re-enable.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = isGlobalSoundEnabled,
                        onCheckedChange = { viewModel.setGlobalSoundEnabled(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.Black,
                            checkedTrackColor = Color.White,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color.White.copy(alpha = 0.15f)
                        ),
                        modifier = Modifier.testTag("global_audio_toggle")
                    )
                }
            }

            Text("CATEGORIZED SECTIONS", color = Color.White.copy(0.4f), fontSize = 10.sp, fontWeight = FontWeight.Black)

            // Categorized Section: Permanent
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("PERMANENT CHANNELS (ANCHORS / FIXED)", color = Color(0xFFE5C05B), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    
                    ChannelAudioRowWithPreview(
                        label = "Permanent Alarm (Start & End)",
                        uriVal = alarmPermanentUri,
                        channelKey = "alarm_permanent",
                        viewModel = viewModel,
                        onPickClick = { activePickingChannel = "alarm_permanent"; audioLauncher.launch(arrayOf("audio/*")) },
                        onResetClick = { viewModel.setAlarmPermanentUri("") }
                    )

                    ChannelAudioRowWithPreview(
                        label = "Permanent Warning (Pre-alerts)",
                        uriVal = notificationPermanentUri,
                        channelKey = "notification_permanent",
                        viewModel = viewModel,
                        onPickClick = { activePickingChannel = "notification_permanent"; audioLauncher.launch(arrayOf("audio/*")) },
                        onResetClick = { viewModel.setNotificationPermanentUri("") }
                    )
                }
            }

            // Categorized Section: Important
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("IMPORTANT CHANNELS (PRIORITY TASKS)", color = Color(0xFFFFB300), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    
                    ChannelAudioRowWithPreview(
                        label = "Important Alarm",
                        uriVal = alarmImportantUri,
                        channelKey = "alarm_important",
                        viewModel = viewModel,
                        onPickClick = { activePickingChannel = "alarm_important"; audioLauncher.launch(arrayOf("audio/*")) },
                        onResetClick = { viewModel.setAlarmImportantUri("") }
                    )

                    ChannelAudioRowWithPreview(
                        label = "Important Warning",
                        uriVal = notificationImportantUri,
                        channelKey = "notification_important",
                        viewModel = viewModel,
                        onPickClick = { activePickingChannel = "notification_important"; audioLauncher.launch(arrayOf("audio/*")) },
                        onResetClick = { viewModel.setNotificationImportantUri("") }
                    )
                }
            }

            // Categorized Section: Normal
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.03f)),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("NORMAL CHANNELS (ROUTINE TASKS)", color = Color.White.copy(0.7f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    
                    ChannelAudioRowWithPreview(
                        label = "Normal Alarm",
                        uriVal = alarmNormalUri,
                        channelKey = "alarm_normal",
                        viewModel = viewModel,
                        onPickClick = { activePickingChannel = "alarm_normal"; audioLauncher.launch(arrayOf("audio/*")) },
                        onResetClick = { viewModel.setAlarmNormalUri("") }
                    )

                    ChannelAudioRowWithPreview(
                        label = "Normal Warning",
                        uriVal = notificationNormalUri,
                        channelKey = "notification_normal",
                        viewModel = viewModel,
                        onPickClick = { activePickingChannel = "notification_normal"; audioLauncher.launch(arrayOf("audio/*")) },
                        onResetClick = { viewModel.setNotificationNormalUri("") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun TaskDetailDialog(
    task: Task,
    viewModel: TimetableViewModel,
    currentLanguage: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var isEditing by remember { mutableStateOf(false) }

    // Local mutable states for editing task details
    var editNameEnglish by remember { mutableStateOf(task.nameEnglish) }
    var editNameUrdu by remember { mutableStateOf(task.nameUrdu) }
    var editStart by remember { mutableStateOf(task.startTime) }
    var editEnd by remember { mutableStateOf(task.endTime) }
    var editIsPermanent by remember { mutableStateOf(task.isPermanent) }
    var editIsImportant by remember { mutableStateOf(task.isImportant) }
    var editWeekdaysList by remember {
        mutableStateOf(task.targetWeekdays.map { it == '1' })
    }

    val isUrdu = (currentLanguage == "Urdu")

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // DIALOG HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isEditing) {
                            if (isUrdu) "ٹاسک کی ترمیم" else "Edit Task"
                        } else {
                            if (isUrdu) "ٹاسک کی تفصیلات" else "Task Details"
                        },
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                if (!isEditing) {
                    // ================= VIEW STATE =================
                    val displayName = if (isUrdu && task.nameUrdu.isNotBlank()) task.nameUrdu else task.nameEnglish
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )

                    // Full Task Status (Permanent, Important, etc.) in one line
                    val statuses = remember(task) {
                        val list = mutableListOf<String>()
                        if (task.isPermanent) {
                            list.add(if (isUrdu) "مستقل" else "Permanent")
                        } else {
                            list.add(if (isUrdu) "آج" else "Today")
                        }
                        if (task.isImportant) {
                            list.add(if (isUrdu) "اہم" else "Important")
                        }
                        if (task.isFixedPrayer) {
                            list.add(if (isUrdu) "لازمی" else "Mandatory")
                        }
                        list.joinToString(" • ")
                    }

                    Text(
                        text = statuses,
                        color = Color(0xFF00FFCC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 2.dp).testTag("full_task_status_line")
                    )

                    // Timeslots and visual markers
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.DateRange,
                            contentDescription = null,
                            tint = Color(0xFF00FFCC),
                            modifier = Modifier.size(18.dp)
                        )
                        Column {
                            Text(
                                text = if (isUrdu) "فعال وقت" else "Time Window",
                                color = Color.White.copy(alpha = 0.5f),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${task.startTime} - ${task.endTime}",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val isLocked = task.isCompleted || task.isFailed || task.dateString < viewModel.getTodayDateString()

                    if (isLocked) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF301515)),
                            border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.4f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = "Locked Record", tint = Color(0xFFFF5252))
                                Text(
                                    text = if (isUrdu) "یہ ریکارڈ لاک ہے۔ مکمل شدہ، معطل شدہ، یا ماضی کے ٹاسکس کو تبدیل یا حذف نہیں کیا جا سکتا۔" else "Locked: Completed, expired, or historical tasks cannot be modified.",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else {
                        // VIEW EDIT TOGGLE ACTION ENTRY
                        Button(
                            onClick = { isEditing = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().testTag("edit_task_details_btn")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "Edit", tint = Color.Black, modifier = Modifier.size(16.dp))
                                Text(
                                    text = if (isUrdu) "ٹاسک کی تفصیلات تبدیل کریں" else "Edit Task Details",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // CLEARLY SEPARATED DELETE OPTION
                        if (!task.isFixedPrayer) {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f), modifier = Modifier.padding(vertical = 4.dp))

                            Button(
                                onClick = {
                                    viewModel.deleteTask(task)
                                    onDismiss()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF401515)),
                                border = BorderStroke(1.dp, Color(0xFFFF5252).copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("delete_unlocked_task_btn")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = Color(0xFFFF5252),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (isUrdu) "مٹائیں" else "Delete Task",
                                        color = Color(0xFFFF5252),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }

                } else {
                    // ================= EDIT STATE =================
                    // Task name English Input Field
                    OutlinedTextField(
                        value = editNameEnglish,
                        onValueChange = { editNameEnglish = it },
                        label = { Text("Task Name (English)", color = Color.White.copy(alpha = 0.6f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FFCC),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("edit_eng_name_input")
                    )

                    // Task name Urdu Input Field
                    OutlinedTextField(
                        value = editNameUrdu,
                        onValueChange = { editNameUrdu = it },
                        label = { Text("ٹاسک کا نام (اردو)", color = Color.White.copy(alpha = 0.6f)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFF00FFCC),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.4f)
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("edit_urdu_name_input")
                    )

                    // Target Timeslots edit fields
                    TimeSelectionRow(
                        label = if (isUrdu) "شروع کا وقت" else "Start Time",
                        timeValue = editStart,
                        onTimeChanged = { editStart = it }
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    TimeSelectionRow(
                        label = if (isUrdu) "ختم ہونے کا وقت" else "End Time",
                        timeValue = editEnd,
                        onTimeChanged = { editEnd = it }
                    )

                    // Repeating / Permanent Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = editIsPermanent,
                            onCheckedChange = { newValue ->
                                editIsPermanent = newValue
                                if (newValue) {
                                    editWeekdaysList = listOf(true, true, true, true, true, true, true)
                                } else {
                                    editWeekdaysList = listOf(false, false, false, false, false, false, false)
                                }
                            },
                            colors = CheckboxDefaults.colors(checkedColor = Color.White, checkmarkColor = Color.Black)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isUrdu) "ہر روز دہرائیں (مستقل ٹاسک)" else "Repeat Daily (Permanent Task)",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    // Important priority toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = editIsImportant,
                            onCheckedChange = { editIsImportant = it },
                            colors = CheckboxDefaults.colors(checkedColor = Color(0xFFFFB300), checkmarkColor = Color.Black)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isUrdu) "اہم / بہترین الارم ترجیح" else "Mark as Important / High Priority",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                    // D. CHIPS WEEKDAYS SELECTOR ROW
                    Text(
                        text = if (isUrdu) "ھدف کے ایام" else "Target Weekdays",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    val daysKeys = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        daysKeys.forEachIndexed { idx, key ->
                            val isSelected = editWeekdaysList[idx]
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(32.dp)
                                    .background(
                                        color = if (isSelected) Color.White else Color.White.copy(alpha = 0.1f),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                    .clickable {
                                        val next = editWeekdaysList.toMutableList()
                                        next[idx] = !next[idx]
                                        editWeekdaysList = next
                                        editIsPermanent = next.any { it }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = Translations.get(key, currentLanguage),
                                    color = if (isSelected) Color.Black else Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    // SAVE / BACK ACTION FOOTERS
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                val savedWeekdays = editWeekdaysList.map { if (it) '1' else '0' }.joinToString("")
                                val updatedTask = task.copy(
                                    nameEnglish = editNameEnglish,
                                    nameUrdu = editNameUrdu,
                                    startTime = editStart,
                                    endTime = editEnd,
                                    isPermanent = editIsPermanent,
                                    isImportant = editIsImportant,
                                    targetWeekdays = savedWeekdays
                                )
                                viewModel.updateTask(updatedTask, context)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00FFCC)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f).testTag("edit_save_btn")
                        ) {
                            Text(
                                text = if (isUrdu) "تبدیلی محفوظ کریں" else "Save Changes",
                                color = Color.Black,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        }

                        Button(
                            onClick = { isEditing = false },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = if (isUrdu) "کینسل" else "Cancel",
                                color = Color.White,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun timeToSeconds(timeStr: String): Int {
    val clean = timeStr.trim().uppercase()
    val isPm = clean.contains("PM")
    val isAm = clean.contains("AM")
    
    val timeWithoutAmPm = clean.replace("AM", "").replace("PM", "").trim()
    val parts = timeWithoutAmPm.split(":")
    if (parts.isEmpty()) return 0
    
    var hour = parts[0].toIntOrNull() ?: 0
    val min = if (parts.size > 1) parts[1].toIntOrNull() ?: 0 else 0
    val sec = if (parts.size > 2) parts[2].toIntOrNull() ?: 0 else 0
    
    if (isPm || isAm) {
        if (isPm && hour < 12) hour += 12
        if (isAm && hour == 12) hour = 0
    }
    return hour * 3600 + min * 60 + sec
}

private fun formatCountdown(secTotal: Int): String {
    if (secTotal <= 0) return "00:00"
    val h = secTotal / 3600
    val m = (secTotal % 3600) / 60
    val s = secTotal % 60
    return if (h > 0) {
        String.format("%02d:%02d:%02d", h, m, s)
    } else {
        String.format("%02d:%02d", m, s)
    }
}

@Composable
fun HistoryLogsDialog(
    viewModel: com.example.ui.TimetableViewModel,
    currentLanguage: String,
    onDismiss: () -> Unit
) {
    val allTasks by viewModel.allHistoryTasks.collectAsStateWithLifecycle()
    val isUrdu = (currentLanguage == "Urdu")

    // Group tasks by date
    val groupedByDate = remember(allTasks) {
        allTasks.groupBy { it.dateString }
    }

    val sortedDates = remember(groupedByDate) {
        groupedByDate.keys.sortedDescending() // newest days first
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF121212)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // HEADER
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isUrdu) "کارکردگی کی ہسٹری" else "Compliance & History Logs",
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.15f), modifier = Modifier.padding(vertical = 12.dp))

                if (sortedDates.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isUrdu) "کوئی ریکارڈ دستیاب نہیں ہے۔" else "No history records found yet.",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    // TABLE COLUMN HEADERS
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isUrdu) "تاریخ" else "Date",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1.2f)
                        )
                        Text(
                            text = if (isUrdu) "ٹوٹل" else "Total",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(0.7f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (isUrdu) "مکمل" else "Comp.",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(0.7f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "%",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(0.7f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = if (isUrdu) "حالت" else "Status",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1.0f),
                            textAlign = TextAlign.End
                        )
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.15f))

                    // TABLE SCROLLABLE ROWS
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(sortedDates.size) { idx ->
                            val date = sortedDates[idx]
                            val dayTasks = groupedByDate[date] ?: emptyList()
                            val total = dayTasks.size
                            val completed = dayTasks.count { it.isCompleted }
                            val pct = if (total > 0) (completed * 100) / total else 0
                            val statusText = when {
                                pct >= 85 -> if (isUrdu) "اشرافیہ 🏆" else "Elite 🏆"
                                pct >= 50 -> if (isUrdu) "سنجیدہ ⚡" else "Serious ⚡"
                                pct >= 30 -> if (isUrdu) "معمولی 👍" else "Normal 👍"
                                else -> if (isUrdu) "سست 😴" else "Lazy 😴"
                            }
                            val statusColor = when {
                                pct >= 85 -> Color(0xFF00FFCC)
                                pct >= 50 -> Color(0xFF56E39F)
                                pct >= 30 -> Color(0xFFFFD166)
                                else -> Color(0xFFFF5252)
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (idx % 2 == 1) Color.White.copy(alpha = 0.02f) else Color.Transparent
                                    )
                                    .padding(vertical = 10.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = date,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.weight(1.2f)
                                )
                                Text(
                                    text = "$total",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(0.7f),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "$completed",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.weight(0.7f),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = "$pct%",
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(0.7f),
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1.0f),
                                    textAlign = TextAlign.End
                                )
                            }

                            if (idx < sortedDates.lastIndex) {
                                HorizontalDivider(color = Color.White.copy(alpha = 0.05f))
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isUrdu) "بند کریں" else "Dismiss",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

