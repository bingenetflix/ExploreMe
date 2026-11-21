package com.local.exploreme

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

enum class ExploreTab {
    PHOTOS, ALBUMS, STORIES, MORE
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExploreMeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = BeigeBg
                ) {
                    ExploreMeApp()
                }
            }
        }
    }
}

@Composable
fun ExploreMeApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val repository = remember { LocalGalleryRepository(context) }

    val requiredPermission = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, requiredPermission) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    var currentTab by remember { mutableStateOf(ExploreTab.PHOTOS) }
    var photos by remember { mutableStateOf<List<LocalPhoto>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var isLoading by remember { mutableStateOf(false) }

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    var openedAlbum by remember { mutableStateOf<PhotoAlbum?>(null) }
    var viewingPhoto by remember { mutableStateOf<LocalPhoto?>(null) }

    val thumbCache = remember { mutableStateMapOf<Long, ImageBitmap?>() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    fun reloadGallery() {
        if (!hasPermission) return
        scope.launch {
            isLoading = true
            val loadedPhotos = withContext(Dispatchers.IO) { repository.loadAllPhotos() }
            val favs = repository.getFavoriteIds()
            photos = loadedPhotos
            favoriteIds = favs
            isLoading = false
        }
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            Toast.makeText(context, "Photo moved to trash", Toast.LENGTH_SHORT).show()
            viewingPhoto = null
            reloadGallery()
        }
    }

    // Automatically refresh when returning from System Camera or Editor
    DisposableEffect(lifecycleOwner, hasPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && hasPermission) {
                reloadGallery()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            reloadGallery()
        } else {
            permLauncher.launch(requiredPermission)
        }
    }

    // Prewarm initial visible thumbnails on IO thread for zero-jitter startup
    LaunchedEffect(photos) {
        if (photos.isNotEmpty()) {
            val topBatch = photos.take(20).filter { !thumbCache.containsKey(it.id) }
            if (topBatch.isNotEmpty()) {
                val decoded = withContext(Dispatchers.IO) {
                    topBatch.associate { p ->
                        p.id to repository.decodeDownsampledImage(p.contentUri, targetMaxPx = 420)
                    }
                }
                thumbCache.putAll(decoded)
            }
        }
    }

    val albums = remember(photos, favoriteIds) {
        repository.groupAlbums(photos, favoriteIds)
    }

    when {
        viewingPhoto != null -> {
            val activePhoto = viewingPhoto!!
            BackHandler { viewingPhoto = null }
            FullScreenPhotoViewer(
                photo = activePhoto,
                isFavorite = activePhoto.id in favoriteIds,
                repository = repository,
                onBack = { viewingPhoto = null },
                onToggleFavorite = {
                    favoriteIds = repository.toggleFavorite(activePhoto.id)
                },
                onShare = { repository.sharePhoto(activePhoto.contentUri) },
                onEdit = { repository.editPhotoInSystem(activePhoto.contentUri) },
                onDelete = {
                    val sender = repository.buildDeleteRequestSender(activePhoto.contentUri)
                    if (sender != null) {
                        deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
                    } else {
                        viewingPhoto = null
                        reloadGallery()
                    }
                }
            )
        }

        openedAlbum != null -> {
            val album = openedAlbum!!
            BackHandler { openedAlbum = null }
            AlbumDetailGridScreen(
                album = album,
                thumbCache = thumbCache,
                repository = repository,
                onBack = { openedAlbum = null },
                onOpenPhoto = { viewingPhoto = it }
            )
        }

        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BeigeBg)
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                when (currentTab) {
                    ExploreTab.PHOTOS -> {
                        GalleryHomeScreen(
                            photos = photos,
                            albums = albums,
                            isLoading = isLoading,
                            hasPermission = hasPermission,
                            showSearch = showSearch,
                            searchQuery = searchQuery,
                            thumbCache = thumbCache,
                            repository = repository,
                            onToggleSearch = {
                                showSearch = !showSearch
                                if (!showSearch) searchQuery = ""
                            },
                            onSearchChange = { searchQuery = it },
                            onRequestPermission = { permLauncher.launch(requiredPermission) },
                            onRefresh = { reloadGallery() },
                            onOpenAlbum = { openedAlbum = it },
                            onSeeAllAlbums = { currentTab = ExploreTab.ALBUMS },
                            onOpenPhoto = { viewingPhoto = it }
                        )
                    }

                    ExploreTab.ALBUMS -> {
                        AllAlbumsGridScreen(
                            albums = albums,
                            thumbCache = thumbCache,
                            repository = repository,
                            onOpenAlbum = { openedAlbum = it }
                        )
                    }

                    ExploreTab.STORIES -> {
                        MemoriesStoriesScreen(
                            albums = albums,
                            thumbCache = thumbCache,
                            repository = repository,
                            onOpenAlbum = { openedAlbum = it }
                        )
                    }

                    ExploreTab.MORE -> {
                        MoreOptionsScreen(
                            totalPhotos = photos.size,
                            totalAlbums = albums.size,
                            totalFavorites = favoriteIds.size,
                            onRefresh = {
                                reloadGallery()
                                Toast.makeText(context, "Gallery refreshed", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                // Floating Camera Button sitting neatly above the right side of the capsule bar
                Surface(
                    onClick = { repository.openSystemCamera() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 22.dp, bottom = 92.dp)
                        .size(54.dp)
                        .shadow(
                            elevation = 8.dp,
                            shape = CircleShape,
                            ambientColor = EspressoInk.copy(alpha = 0.14f),
                            spotColor = EspressoInk.copy(alpha = 0.20f)
                        ),
                    shape = CircleShape,
                    color = BeigeCard,
                    border = BorderStroke(1.dp, HairlineSand)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "Open Camera",
                            tint = EspressoInk,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Bottom Floating Warm-Beige Capsule Bar (Photos, Albums, Stories, More)
                BottomGalleryCapsuleBar(
                    selectedTab = currentTab,
                    onSelectTab = { currentTab = it },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
fun GalleryHomeScreen(
    photos: List<LocalPhoto>,
    albums: List<PhotoAlbum>,
    isLoading: Boolean,
    hasPermission: Boolean,
    showSearch: Boolean,
    searchQuery: String,
    thumbCache: MutableMap<Long, ImageBitmap?>,
    repository: LocalGalleryRepository,
    onToggleSearch: () -> Unit,
    onSearchChange: (String) -> Unit,
    onRequestPermission: () -> Unit,
    onRefresh: () -> Unit,
    onOpenAlbum: (PhotoAlbum) -> Unit,
    onSeeAllAlbums: () -> Unit,
    onOpenPhoto: (LocalPhoto) -> Unit
) {
    var showHeaderMenu by remember { mutableStateOf(false) }

    val filteredPhotos = remember(photos, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) photos else photos.filter {
            it.displayName.lowercase().contains(q) || it.albumName.lowercase().contains(q)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 110.dp)
    ) {
        // Header: "Gallery" + "Your moments. Beautifully organized." + Search & More buttons
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 22.dp, end = 18.dp, top = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Gallery",
                        color = EspressoInk,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.6).sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Your moments. Beautifully organized.",
                        color = TaupeMuted,
                        fontSize = 13.sp
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Surface(
                        onClick = onToggleSearch,
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = BeigePillSurface
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (showSearch) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = "Search",
                                tint = EspressoInk,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }

                    Box {
                        Surface(
                            onClick = { showHeaderMenu = true },
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            color = BeigePillSurface
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More",
                                    tint = EspressoInk,
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = showHeaderMenu,
                            onDismissRequest = { showHeaderMenu = false },
                            containerColor = BeigeCard
                        ) {
                            DropdownMenuItem(
                                text = { Text("Refresh Photos", color = EspressoInk) },
                                onClick = {
                                    showHeaderMenu = false
                                    onRefresh()
                                }
                            )
                        }
                    }
                }
            }
        }

        if (showSearch) {
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp),
                    singleLine = true,
                    placeholder = { Text("Search photos or albums...", color = TaupeMuted, fontSize = 14.sp) },
                    shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = BeigeCard,
                        unfocusedContainerColor = BeigeCard,
                        focusedBorderColor = EspressoInk,
                        unfocusedBorderColor = HairlineSand,
                        focusedTextColor = EspressoInk,
                        unfocusedTextColor = EspressoInk
                    )
                )
            }
        }

        when {
            !hasPermission -> {
                item {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                    ) {
                        Text(
                            text = "Allow Photo Access",
                            color = EspressoInk,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Needed to organize and display photos stored on your phone offline.",
                            color = TaupeMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = onRequestPermission,
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EspressoInk,
                                contentColor = BeigeCard
                            )
                        ) {
                            Text("Grant Permission", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            isLoading -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = EspressoInk)
                    }
                }
            }

            albums.isEmpty() -> {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No photos found on device", color = TaupeMuted, fontSize = 15.sp)
                    }
                }
            }

            else -> {
                // Featured 3-Card Hero Carousel with Center Elevation & Pagination Dots
                if (searchQuery.isBlank()) {
                    item {
                        val featuredAlbums = albums.take(5)
                        val pagerState = rememberPagerState(
                            initialPage = if (featuredAlbums.size > 1) 1 else 0,
                            pageCount = { featuredAlbums.size }
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            HorizontalPager(
                                state = pagerState,
                                contentPadding = PaddingValues(horizontal = 66.dp),
                                pageSpacing = 12.dp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(246.dp)
                            ) { page ->
                                val album = featuredAlbums[page]
                                val coverPhoto = album.photos.first()

                                LaunchedEffect(coverPhoto.id) {
                                    if (!thumbCache.containsKey(coverPhoto.id)) {
                                        thumbCache[coverPhoto.id] = withContext(Dispatchers.IO) {
                                            repository.decodeDownsampledImage(coverPhoto.contentUri, targetMaxPx = 560)
                                        }
                                    }
                                }

                                val pageOffset = (
                                    (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                ).absoluteValue.coerceIn(0f, 1f)
                                val cardScale = lerp(1f, 0.86f, pageOffset)

                                Surface(
                                    onClick = { onOpenAlbum(album) },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            scaleX = cardScale
                                            scaleY = cardScale
                                        }
                                        .shadow(
                                            elevation = if (pageOffset < 0.3f) 10.dp else 3.dp,
                                            shape = RoundedCornerShape(26.dp),
                                            ambientColor = EspressoInk.copy(alpha = 0.16f),
                                            spotColor = EspressoInk.copy(alpha = 0.22f)
                                        ),
                                    shape = RoundedCornerShape(26.dp),
                                    color = BeigePillSurface
                                ) {
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        val bmp = thumbCache[coverPhoto.id]
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = album.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(92.dp)
                                                .align(Alignment.BottomCenter)
                                                .background(
                                                    Brush.verticalGradient(
                                                        colors = listOf(
                                                            Color.Transparent,
                                                            Color.Black.copy(alpha = 0.62f)
                                                        )
                                                    )
                                                )
                                        )

                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .align(Alignment.BottomCenter)
                                                .padding(horizontal = 16.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = album.name,
                                                    color = Color.White,
                                                    fontSize = 17.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${album.photoCount} photos",
                                                    color = Color.White.copy(alpha = 0.85f),
                                                    fontSize = 12.sp
                                                )
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.22f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                                    contentDescription = "Open ${album.name}",
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                repeat(featuredAlbums.size) { dotIdx ->
                                    val isSelected = pagerState.currentPage == dotIdx
                                    Box(
                                        modifier = Modifier
                                            .size(if (isSelected) 7.dp else 5.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (isSelected) EspressoInk else TaupeMuted.copy(alpha = 0.35f)
                                            )
                                    )
                                }
                            }
                        }
                    }

                    // "Albums" Section Header + "See all ->"
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 22.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Albums",
                                color = EspressoInk,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )

                            TextButton(onClick = onSeeAllAlbums) {
                                Text(
                                    text = "See all",
                                    color = EspressoInk,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = EspressoInk,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    // Horizontal Rounded-Square Album Row
                    item {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 22.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(albums.take(8), key = { it.name }) { album ->
                                val cover = album.photos.first()
                                LaunchedEffect(cover.id) {
                                    if (!thumbCache.containsKey(cover.id)) {
                                        thumbCache[cover.id] = withContext(Dispatchers.IO) {
                                            repository.decodeDownsampledImage(cover.contentUri, targetMaxPx = 280)
                                        }
                                    }
                                }

                                Column(
                                    modifier = Modifier
                                        .width(80.dp)
                                        .clickable { onOpenAlbum(album) }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(BeigePillSurface)
                                            .border(1.dp, HairlineSand, RoundedCornerShape(18.dp))
                                    ) {
                                        val bmp = thumbCache[cover.id]
                                        if (bmp != null) {
                                            Image(
                                                bitmap = bmp,
                                                contentDescription = album.name,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = album.name,
                                        color = EspressoInk,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${album.photoCount}",
                                        color = TaupeMuted,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Recent Photos Grid Preview Section
                item {
                    Text(
                        text = if (searchQuery.isBlank()) "Recent Photos" else "Search Results (${filteredPhotos.size})",
                        color = EspressoInk,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = 22.dp, top = 18.dp, bottom = 10.dp)
                    )
                }

                val rows = filteredPhotos.take(60).chunked(3)
                items(rows) { rowPhotos ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowPhotos.forEach { photo ->
                            LaunchedEffect(photo.id) {
                                if (!thumbCache.containsKey(photo.id)) {
                                    thumbCache[photo.id] = withContext(Dispatchers.IO) {
                                        repository.decodeDownsampledImage(photo.contentUri, targetMaxPx = 320)
                                    }
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(BeigePillSurface)
                                    .clickable { onOpenPhoto(photo) }
                            ) {
                                val bmp = thumbCache[photo.id]
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp,
                                        contentDescription = photo.displayName,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                        repeat(3 - rowPhotos.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenPhotoViewer(
    photo: LocalPhoto,
    isFavorite: Boolean,
    repository: LocalGalleryRepository,
    onBack: () -> Unit,
    onToggleFavorite: () -> Unit,
    onShare: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var fullBitmap by remember(photo.id) { mutableStateOf<ImageBitmap?>(null) }
    var showInfoSheet by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(photo.id) {
        fullBitmap = withContext(Dispatchers.IO) {
            repository.decodeDownsampledImage(photo.contentUri, targetMaxPx = 1440)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14110E))
    ) {
        // Pinch-to-Zoom & Pan Photo Canvas
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(photo.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 4f)
                        scale = newScale
                        if (newScale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (fullBitmap != null) {
                Image(
                    bitmap = fullBitmap!!,
                    contentDescription = photo.displayName,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        },
                    contentScale = ContentScale.Fit
                )
            } else {
                CircularProgressIndicator(color = BeigeCard)
            }
        }

        // Top Floating Translucent Beige Controls (<  |  Heart, Info, More)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp)
                .align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(42.dp),
                shape = CircleShape,
                color = BeigePillSurface.copy(alpha = 0.85f)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = EspressoInk,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = BeigePillSurface.copy(alpha = 0.85f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Favorite",
                            tint = if (isFavorite) HeartRose else EspressoInk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Surface(
                    onClick = { showInfoSheet = true },
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    color = BeigePillSurface.copy(alpha = 0.85f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info",
                            tint = EspressoInk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box {
                    Surface(
                        onClick = { showMoreMenu = true },
                        modifier = Modifier.size(42.dp),
                        shape = CircleShape,
                        color = BeigePillSurface.copy(alpha = 0.85f)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "More",
                                tint = EspressoInk,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    DropdownMenu(
                        expanded = showMoreMenu,
                        onDismissRequest = { showMoreMenu = false },
                        containerColor = BeigeCard
                    ) {
                        DropdownMenuItem(
                            text = { Text("Reset Zoom", color = EspressoInk) },
                            onClick = {
                                showMoreMenu = false
                                scale = 1f
                                offsetX = 0f
                                offsetY = 0f
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Photo Details", color = EspressoInk) },
                            onClick = {
                                showMoreMenu = false
                                showInfoSheet = true
                            }
                        )
                    }
                }
            }
        }

        // Bottom Metadata Pill + Floating Action Capsule (Share, Edit, Delete, More)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = OverlayEspresso
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Photo,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = photo.displayName,
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = photo.formattedDate,
                            color = Color.White.copy(alpha = 0.78f),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                color = BeigePillSurface.copy(alpha = 0.92f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ViewerBottomAction(label = "Share", icon = Icons.Default.Share, onClick = onShare)
                    ViewerBottomAction(label = "Edit", icon = Icons.Default.Edit, onClick = onEdit)
                    ViewerBottomAction(label = "Delete", icon = Icons.Default.Delete, onClick = onDelete)
                    ViewerBottomAction(label = "More", icon = Icons.Default.MoreVert, onClick = { showInfoSheet = true })
                }
            }
        }
    }

    if (showInfoSheet) {
        AlertDialog(
            onDismissRequest = { showInfoSheet = false },
            containerColor = BeigeCard,
            title = { Text("Photo Details", color = EspressoInk, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Name: ${photo.displayName}", color = EspressoInk, fontSize = 13.sp)
                    Text("Date: ${photo.formattedDate}", color = EspressoInk, fontSize = 13.sp)
                    Text("Album: ${photo.albumName}", color = EspressoInk, fontSize = 13.sp)
                    if (photo.width > 0 && photo.height > 0) {
                        Text("Resolution: ${photo.width} × ${photo.height}", color = EspressoInk, fontSize = 13.sp)
                    }
                    Text("Size: ${repository.formatFileSize(photo.sizeBytes)}", color = EspressoInk, fontSize = 13.sp)
                    if (photo.relativePath.isNotBlank()) {
                        Text("Path: ${photo.relativePath}", color = TaupeMuted, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showInfoSheet = false }) {
                    Text("Close", color = EspressoInk, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
fun ViewerBottomAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = EspressoInk, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(text = label, color = EspressoInk, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AlbumDetailGridScreen(
    album: PhotoAlbum,
    thumbCache: MutableMap<Long, ImageBitmap?>,
    repository: LocalGalleryRepository,
    onBack: () -> Unit,
    onOpenPhoto: (LocalPhoto) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BeigeBg)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = EspressoInk
                )
            }
            Column {
                Text(
                    text = album.name,
                    color = EspressoInk,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = "${album.photoCount} photos",
                    color = TaupeMuted,
                    fontSize = 12.sp
                )
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(album.photos, key = { it.id }) { photo ->
                LaunchedEffect(photo.id) {
                    if (!thumbCache.containsKey(photo.id)) {
                        thumbCache[photo.id] = withContext(Dispatchers.IO) {
                            repository.decodeDownsampledImage(photo.contentUri, targetMaxPx = 340)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(BeigePillSurface)
                        .clickable { onOpenPhoto(photo) }
                ) {
                    val bmp = thumbCache[photo.id]
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = photo.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AllAlbumsGridScreen(
    albums: List<PhotoAlbum>,
    thumbCache: MutableMap<Long, ImageBitmap?>,
    repository: LocalGalleryRepository,
    onOpenAlbum: (PhotoAlbum) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = "Albums",
            color = EspressoInk,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(vertical = 14.dp)
        )

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(bottom = 110.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums, key = { it.name }) { album ->
                val cover = album.photos.first()
                LaunchedEffect(cover.id) {
                    if (!thumbCache.containsKey(cover.id)) {
                        thumbCache[cover.id] = withContext(Dispatchers.IO) {
                            repository.decodeDownsampledImage(cover.contentUri, targetMaxPx = 420)
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAlbum(album) }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(22.dp))
                            .background(BeigePillSurface)
                            .border(1.dp, HairlineSand, RoundedCornerShape(22.dp))
                    ) {
                        val bmp = thumbCache[cover.id]
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = album.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = album.name,
                        color = EspressoInk,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${album.photoCount} photos",
                        color = TaupeMuted,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
fun MemoriesStoriesScreen(
    albums: List<PhotoAlbum>,
    thumbCache: MutableMap<Long, ImageBitmap?>,
    repository: LocalGalleryRepository,
    onOpenAlbum: (PhotoAlbum) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Text(
            text = "Stories",
            color = EspressoInk,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 14.dp)
        )
        Text(
            text = "Curated local collections from your folders",
            color = TaupeMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 14.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(bottom = 110.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(albums.take(6), key = { it.name }) { album ->
                val cover = album.photos.first()
                LaunchedEffect(cover.id) {
                    if (!thumbCache.containsKey(cover.id)) {
                        thumbCache[cover.id] = withContext(Dispatchers.IO) {
                            repository.decodeDownsampledImage(cover.contentUri, targetMaxPx = 560)
                        }
                    }
                }

                Surface(
                    onClick = { onOpenAlbum(album) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = BeigePillSurface
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        val bmp = thumbCache[cover.id]
                        if (bmp != null) {
                            Image(
                                bitmap = bmp,
                                contentDescription = album.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.28f))
                        )
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(18.dp)
                        ) {
                            Text(
                                text = album.name,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                            Text(
                                text = "${album.photoCount} moments",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MoreOptionsScreen(
    totalPhotos: Int,
    totalAlbums: Int,
    totalFavorites: Int,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "ExploreMe",
            color = EspressoInk,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = "100% Offline Local Gallery • Zero Cloud Sync",
            color = TaupeMuted,
            fontSize = 13.sp
        )

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = BeigeCard,
            border = BorderStroke(1.dp, HairlineSand)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Total Local Photos", color = TaupeMuted, fontSize = 14.sp)
                    Text("$totalPhotos", color = EspressoInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = HairlineSand)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Local Albums", color = TaupeMuted, fontSize = 14.sp)
                    Text("$totalAlbums", color = EspressoInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
                HorizontalDivider(color = HairlineSand)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Favorites", color = TaupeMuted, fontSize = 14.sp)
                    Text("$totalFavorites", color = EspressoInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Button(
            onClick = onRefresh,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = EspressoInk,
                contentColor = BeigeCard
            )
        ) {
            Text("Rescan Local MediaStore", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun BottomGalleryCapsuleBar(
    selectedTab: ExploreTab,
    onSelectTab: (ExploreTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                ambientColor = EspressoInk.copy(alpha = 0.12f),
                spotColor = EspressoInk.copy(alpha = 0.16f)
            ),
        shape = CircleShape,
        color = BeigePillSurface,
        border = BorderStroke(1.dp, HairlineSand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GalleryNavPill(
                label = "Photos",
                icon = Icons.Default.Photo,
                selected = selectedTab == ExploreTab.PHOTOS,
                onClick = { onSelectTab(ExploreTab.PHOTOS) },
                modifier = Modifier.weight(1f)
            )
            GalleryNavPill(
                label = "Albums",
                icon = Icons.Default.Folder,
                selected = selectedTab == ExploreTab.ALBUMS,
                onClick = { onSelectTab(ExploreTab.ALBUMS) },
                modifier = Modifier.weight(1f)
            )
            GalleryNavPill(
                label = "Stories",
                icon = Icons.Default.PlayCircleOutline,
                selected = selectedTab == ExploreTab.STORIES,
                onClick = { onSelectTab(ExploreTab.STORIES) },
                modifier = Modifier.weight(1f)
            )
            GalleryNavPill(
                label = "More",
                icon = Icons.Default.MoreVert,
                selected = selectedTab == ExploreTab.MORE,
                onClick = { onSelectTab(ExploreTab.MORE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun GalleryNavPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (selected) BeigeActivePill else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = EspressoInk,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                color = EspressoInk,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}
