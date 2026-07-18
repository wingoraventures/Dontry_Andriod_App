package com.dontry.app

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object AuthHelper {

    private const val TAG = "AuthHelper"

    fun ensureUserDoc(
        uid: String,
        name: String,
        contact: String,
        onDone: (isNewUser: Boolean) -> Unit = {}
    ) {
        val db = FirebaseFirestore.getInstance()
        val docRef = db.collection("users").document(uid)
        docRef.get()
            .addOnSuccessListener { snapshot ->
                val hasFullProfile = snapshot.exists() && snapshot.contains("createdAt")

                if (!hasFullProfile) {
                    docRef.set(
                        hashMapOf(
                            "name" to name,
                            "contact" to contact,
                            "createdAt" to System.currentTimeMillis(),
                            "tryonsRemaining" to 20,
                            "tryonPlan" to "Free Credits",
                            "planValidUntil" to null,
                            "hasUsedTestTryons" to false,
                            "activePlanExpiry" to null,
                            "notifiedExpirySoon" to false,
                            "fcmToken" to ""
                        ),
                        SetOptions.merge()
                    ).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            onDone(true)
                        } else {
                            Log.e(TAG, "Failed to CREATE user doc for uid=$uid", task.exception)
                            onDone(true)
                        }
                    }
                } else {

                    val updates = mutableMapOf<String, Any>("contact" to contact)
                    if (name.isNotBlank()) {
                        updates["name"] = name
                    }
                    docRef.update(updates)
                        .addOnCompleteListener { onDone(false) }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to READ user doc for uid=$uid — treating as existing user", e)
                onDone(false)
            }
    }
}