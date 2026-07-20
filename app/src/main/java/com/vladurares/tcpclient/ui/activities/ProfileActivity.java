package com.vladurares.tcpclient.ui.activities;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.vladurares.tcpclient.R;
import com.vladurares.tcpclient.network.PacketRouter;
import com.vladurares.tcpclient.network.TcpConnection;
import com.vladurares.tcpclient.storage.SecureStorage;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;

import chat.network.NetworkPacket;
import chat.network.PacketType;

public class ProfileActivity extends AppCompatActivity {
    private static final String TAG = "ProfileActivity";
    private ImageView imgProfilePic;

    private final ActivityResultLauncher<Intent> pickImageLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri imageUri = result.getData().getData();
                    if (imageUri != null) {
                        processAndUploadImage(imageUri);
                    }
                }
            });

    private final PacketRouter.PacketCallback onProfilePicResponse = packet -> {
        runOnUiThread(() -> {
            try {
                String base64 = new com.google.gson.Gson().fromJson(packet.getPayload(), String.class);
                if (base64 != null && !base64.isEmpty()) {
                    showProfilePic(base64);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load profile pic", e);
            }
        });
    };

    private final PacketRouter.PacketCallback onDeleteResponse = packet -> {
        runOnUiThread(() -> {
            String response = new com.google.gson.Gson().fromJson(packet.getPayload(), String.class);
            if ("OK".equals(response)) {
                Toast.makeText(this, "Account deleted.", Toast.LENGTH_SHORT).show();

                SharedPreferences prefs = SecureStorage.getEncryptedPrefs(this);
                prefs.edit().clear().apply();

                TcpConnection.stopReading();
                TcpConnection.close();

                Intent intent = new Intent(this, LoginActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            } else {
                Toast.makeText(this, "Failed to delete account.", Toast.LENGTH_SHORT).show();
            }
        });
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile);

        imgProfilePic = findViewById(R.id.imgProfilePic);

        String username = getIntent().getStringExtra("USERNAME");
        String email = getIntent().getStringExtra("EMAIL");
        int userId = getIntent().getIntExtra("USER_ID", -1);

        ((TextView) findViewById(R.id.txtUsername)).setText(username != null ? username : "—");
        ((TextView) findViewById(R.id.txtEmail)).setText(email != null ? email : "—");
        ((TextView) findViewById(R.id.txtUserId)).setText(userId > 0 ? String.valueOf(userId) : "—");

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        findViewById(R.id.cardProfilePic).setOnClickListener(v -> {
            Intent pick = new Intent(Intent.ACTION_PICK);
            pick.setType("image/*");
            pickImageLauncher.launch(pick);
        });

        findViewById(R.id.btnDeleteAccount).setOnClickListener(v -> confirmDeleteAccount());

        TcpConnection.sendPacket(new NetworkPacket(
                PacketType.GET_PROFILE_PICTURE_REQUEST,
                TcpConnection.getCurrentUserId(),
                TcpConnection.getCurrentUserId()
        ));
    }

    @Override
    protected void onResume() {
        super.onResume();
        PacketRouter r = PacketRouter.getInstance();
        r.on(PacketType.GET_PROFILE_PICTURE_RESPONSE, onProfilePicResponse);
        r.on(PacketType.DELETE_ACCOUNT_RESPONSE, onDeleteResponse);
    }

    @Override
    protected void onPause() {
        super.onPause();
        PacketRouter r = PacketRouter.getInstance();
        r.off(PacketType.GET_PROFILE_PICTURE_RESPONSE, onProfilePicResponse);
        r.off(PacketType.DELETE_ACCOUNT_RESPONSE, onDeleteResponse);
    }

    private Bitmap fixRotation(Uri imageUri, Bitmap bitmap) {
        try {
            InputStream is = getContentResolver().openInputStream(imageUri);
            if (is == null) return bitmap;

            androidx.exifinterface.media.ExifInterface exif =
                    new androidx.exifinterface.media.ExifInterface(is);
            is.close();

            int orientation = exif.getAttributeInt(
                    androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                    androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            );

            int degrees = 0;
            switch (orientation) {
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90:  degrees = 90;  break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180: degrees = 180; break;
                case androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270: degrees = 270; break;
            }

            if (degrees == 0) return bitmap;

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            matrix.postRotate(degrees);
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        } catch (Exception e) {
            Log.w(TAG, "EXIF rotation fix failed", e);
            return bitmap;
        }
    }

    private void processAndUploadImage(Uri imageUri) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(imageUri);
                Bitmap original = BitmapFactory.decodeStream(is);
                if (is != null) is.close();

                if (original == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show());
                    return;
                }

                original = fixRotation(imageUri, original);

                int maxSize = 400;
                int w = original.getWidth();
                int h = original.getHeight();
                float scale = Math.min((float) maxSize / w, (float) maxSize / h);
                if (scale < 1) {
                    w = Math.round(w * scale);
                    h = Math.round(h * scale);
                }
                Bitmap resized = Bitmap.createScaledBitmap(original, w, h, true);

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                resized.compress(Bitmap.CompressFormat.JPEG, 85, baos);
                String base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);

                TcpConnection.sendPacket(new NetworkPacket(
                        PacketType.UPDATE_PROFILE_PICTURE_REQUEST,
                        TcpConnection.getCurrentUserId(),
                        base64
                ));

                runOnUiThread(() -> showProfilePic(base64));

                Log.i(TAG, "Profile pic uploaded: " + base64.length() + " chars");

            } catch (Exception e) {
                Log.e(TAG, "Error processing image", e);
                runOnUiThread(() -> Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void showProfilePic(String base64) {
        try {
            byte[] bytes = Base64.decode(base64, Base64.NO_WRAP);
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                imgProfilePic.setImageBitmap(bmp);
                imgProfilePic.setPadding(0, 0, 0, 0);
                imgProfilePic.setImageTintList(null);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode profile pic", e);
        }
    }

    private void confirmDeleteAccount() {
        new AlertDialog.Builder(this, R.style.DialogSmecher)
                .setTitle("Delete Account")
                .setMessage("This will permanently delete your account, all messages, and all conversations. This action cannot be undone.\n\nAre you sure?")
                .setPositiveButton("Delete", (d, w) -> {
                    new AlertDialog.Builder(this, R.style.DialogSmecher)
                            .setTitle("Final Confirmation")
                            .setMessage("Type DELETE to confirm.")
                            .setView(createDeleteInput())
                            .setPositiveButton("Confirm", (d2, w2) -> {
                                android.widget.EditText input = (android.widget.EditText) ((AlertDialog) d2).findViewById(android.R.id.edit);
                                if (input != null && "DELETE".equals(input.getText().toString().trim())) {
                                    TcpConnection.sendPacket(new NetworkPacket(
                                            PacketType.DELETE_ACCOUNT_REQUEST,
                                            TcpConnection.getCurrentUserId(),
                                            TcpConnection.getCurrentUserId()
                                    ));
                                } else {
                                    Toast.makeText(this, "Cancelled.", Toast.LENGTH_SHORT).show();
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private android.widget.EditText createDeleteInput() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setId(android.R.id.edit);
        input.setHint("Type DELETE");
        input.setTextColor(android.graphics.Color.WHITE);
        input.setHintTextColor(android.graphics.Color.LTGRAY);
        return input;
    }
}
