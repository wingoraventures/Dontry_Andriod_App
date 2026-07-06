package com.dontry.app

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

object AuthHelper {

    private const val TAG = "AuthHelper"

    // onDone receives a boolean: true => newly created user doc (first-time user), false => existing user
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
                if (!snapshot.exists()) {
                    docRef.set(
                        hashMapOf(
                            "name" to name,
                            "contact" to contact,
                            "createdAt" to System.currentTimeMillis(),
                            "tryonsRemaining" to 0,
                            "tryonPlan" to "Free Credits",
                            "planValidUntil" to null,
                            "hasUsedTestTryons" to false,
                            "activePlanExpiry" to null,
                            "notifiedExpirySoon" to false,
                            "fcmToken" to ""
                        )
                    ).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            onDone(true)
                        } else {
                            Log.e(TAG, "Failed to CREATE user doc for uid=$uid", task.exception)
                            // Doc creation failed — do NOT claim success, but still let user in
                            // so they aren't stuck. Credits will be missing until this is fixed.
                            onDone(true)
                        }
                    }
                } else {
                    docRef.update(mapOf("name" to name, "contact" to contact))
                        .addOnCompleteListener { onDone(false) }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to READ user doc for uid=$uid — treating as existing user", e)
                onDone(false)
            }
    }


}