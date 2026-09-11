package com.yourname.birder

import java.io.File

data class BirdSong(
    val file: File,
    val scientificName: String,
    val commonName: String,
    val slovenianName: String,
    val soundType: String,       // "song", "call", etc.
    val quality: String,
    val recordist: String,
    val country: String,
    val date: String,
    val xcUrl: String
)