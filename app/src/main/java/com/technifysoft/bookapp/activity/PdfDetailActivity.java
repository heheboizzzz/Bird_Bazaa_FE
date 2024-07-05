package com.technifysoft.bookapp.activity;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import com.technifysoft.bookapp.MyApplication;
import com.technifysoft.bookapp.R;
import com.technifysoft.bookapp.adapter.AdapterComment;
import com.technifysoft.bookapp.databinding.ActivityPdfDetailBinding;
import com.technifysoft.bookapp.databinding.DialogCommentAddBinding;
import com.technifysoft.bookapp.model.ModelComment;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

public class PdfDetailActivity extends AppCompatActivity {
    private static final String TAG_DOWNLOAD = "DOWNLOAD_TAG";
    String bookId, bookTitle, bookUrl;
    boolean isInMyFavorite = false;
    private ActivityPdfDetailBinding binding;
    private FirebaseAuth firebaseAuth;
    private ProgressDialog progressDialog;
    private ArrayList<ModelComment> commentArrayList;
    private AdapterComment adapterComment;
    private String comment = "";

    private static final String CHANNEL_ID = "my_channel";

    private NotificationManager notificationManager;
    private ActivityResultLauncher<String> requestPermissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
        if (isGranted) {
            Log.d(TAG_DOWNLOAD, "Permission Granted");
            MyApplication.downloadBook(this, "" + bookId, "" + bookTitle, "" + bookUrl);
        } else {
            Log.d(TAG_DOWNLOAD, "Permission was denied...: ");
            Toast.makeText(this, "Permission was denied...", Toast.LENGTH_SHORT).show();
        }
    });

    private void sendNotification() {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, "1").setSmallIcon(R.drawable.notifications_icon).setContentTitle("Notification!!!").setContentText("You have added a new item to cart").setPriority(NotificationCompat.PRIORITY_DEFAULT);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager != null) {
            notificationManager.notify(getNotificationId(), builder.build());
        }
    }

    private int getNotificationId() {
        return (int) new Date().getTime();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityPdfDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //get data from intent e.g. bookId
        Intent intent = getIntent();
        bookId = intent.getStringExtra("bookId");

        //at start hide download button, because need book url that will load later in function loadBookDetails();
        binding.downloadBookBtn.setVisibility(View.GONE);

        //init progress dialog
        progressDialog = new ProgressDialog(this);
        progressDialog.setTitle("Please wait");
        progressDialog.setCanceledOnTouchOutside(false);

        firebaseAuth = FirebaseAuth.getInstance();
        if (firebaseAuth.getCurrentUser() != null) {
            checkIsFavorite();
        }

        loadBookDetails();
        loadComments();
        //increment book view count, whenever this page starts
        MyApplication.incrementBookViewCount(bookId);


        //handle click, go back
        binding.backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBackPressed();
            }
        });

        //handle click, open to view pdf
        binding.readBookBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent1 = new Intent(PdfDetailActivity.this, PdfViewActivity.class);
                intent1.putExtra("bookId", bookId);
                startActivity(intent1);
            }
        });

        //handle click, download pdf
        binding.downloadBookBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG_DOWNLOAD, "onClick: Checking permission");
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    MyApplication.downloadBook(PdfDetailActivity.this, "" + bookId, "" + bookTitle, "" + bookUrl);
                } else {
                    if (ContextCompat.checkSelfPermission(PdfDetailActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                        Log.d(TAG_DOWNLOAD, "onClick: Permission already granted, can download book");
                        MyApplication.downloadBook(PdfDetailActivity.this, "" + bookId, "" + bookTitle, "" + bookUrl);
                    } else {
                        Log.d(TAG_DOWNLOAD, "onClick: Permission was not granted, request permission...");
                        requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                    }
                }

            }
        });
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        //handle click, add/remove favorite
        binding.favoriteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (firebaseAuth.getCurrentUser() == null) {
                    Toast.makeText(PdfDetailActivity.this, "You're not logged in", Toast.LENGTH_SHORT).show();
                } else {
                    if (isInMyFavorite) {
                        //in favorite, remove from favorite
                        MyApplication.removeFromFavorite(PdfDetailActivity.this, bookId);
                        createNotification();
                        showRemoveNotification();
                    } else {
                        //not in favorite, add to favorite
                        MyApplication.addToFavorite(PdfDetailActivity.this, bookId);
                        createNotification();
                        showNotification();
                    }
                }
            }
        });

        //handle click, show comment add dialog
        binding.addCommentBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                /*Requirements: User must be logged in to add comment*/
                if (firebaseAuth.getCurrentUser() == null) {
                    Toast.makeText(PdfDetailActivity.this, "You're not logged in...", Toast.LENGTH_SHORT).show();
                } else {
                    addCommentDialog();
                }
            }
        });


    }

    private void loadComments() {
        //init arraylist before adding data into it
        commentArrayList = new ArrayList<>();

        //db path to load comments
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Books");
        ref.child(bookId).child("Comments").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                //clear arraylist before start adding data into it
                commentArrayList.clear();
                for (DataSnapshot ds : snapshot.getChildren()) {
                    //get data as model, spellings of variables in model must be as same as in firebase
                    ModelComment model = ds.getValue(ModelComment.class);
                    //add to arraylist
                    commentArrayList.add(model);
                }
                //setup adapter
                adapterComment = new AdapterComment(PdfDetailActivity.this, commentArrayList);
                //set adapter to recyclerview
                binding.commentsRv.setAdapter(adapterComment);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {

            }
        });
    }

    private void addCommentDialog() {
        //inflate bind view for dialog
        DialogCommentAddBinding commentAddBinding = DialogCommentAddBinding.inflate(LayoutInflater.from(this));

        //setup alert dialog builder
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.CustomDialog);
        builder.setView(commentAddBinding.getRoot());

        //create and show alert dialog
        AlertDialog alertDialog = builder.create();
        alertDialog.show();

        //handle click, dismis dialog
        commentAddBinding.backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                alertDialog.dismiss();
            }
        });

        //handle click, add comment
        commentAddBinding.submitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //get data
                comment = commentAddBinding.commentEt.getText().toString().trim();
                //validate data
                if (TextUtils.isEmpty(comment)) {
                    Toast.makeText(PdfDetailActivity.this, "Enter your comment...", Toast.LENGTH_SHORT).show();
                } else {
                    alertDialog.dismiss();
                    addComment();
                }
            }
        });

    }

    private void addComment() {
        //show progress
        progressDialog.setMessage("Adding comment...");
        progressDialog.show();

        //timestamp for comment id, comment time
        String timestamp = "" + System.currentTimeMillis();

        //setup data to add in db for comment
        HashMap<String, Object> hashMap = new HashMap<>();
        hashMap.put("id", "" + timestamp);
        hashMap.put("bookId", "" + bookId);
        hashMap.put("timestamp", "" + timestamp);
        hashMap.put("comment", "" + comment);
        hashMap.put("uid", "" + firebaseAuth.getUid());

        //DB path to add data into it
        //Books > bookId > Comments > commentId > commentData
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Books");
        ref.child(bookId).child("Comments").child(timestamp).setValue(hashMap).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void unused) {
                Toast.makeText(PdfDetailActivity.this, "Comment Added...", Toast.LENGTH_SHORT).show();
                progressDialog.dismiss();
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(@NonNull Exception e) {
                //failed to add comment
                progressDialog.dismiss();
                Toast.makeText(PdfDetailActivity.this, "Failed to add comment due to " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

    }

    private void loadBookDetails() {
        DatabaseReference ref = FirebaseDatabase.getInstance().getReference("Books");
        ref.child(bookId).addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                //get data
                bookTitle = "" + snapshot.child("title").getValue();
                String description = "" + snapshot.child("description").getValue();
                String categoryId = "" + snapshot.child("categoryId").getValue();
                String viewsCount = "" + snapshot.child("viewsCount").getValue();
                String downloadsCount = "" + snapshot.child("downloadsCount").getValue();
                bookUrl = "" + snapshot.child("url").getValue();
                String timestamp = "" + snapshot.child("timestamp").getValue();

                //required data is loaded, show download button
                binding.downloadBookBtn.setVisibility(View.VISIBLE);

                //format date
                String date = MyApplication.formatTimestamp(Long.parseLong(timestamp));

                MyApplication.loadCategory("" + categoryId, binding.categoryTv);
                MyApplication.loadPdfFromUrlSinglePage("" + bookUrl, "" + bookTitle, binding.pdfView, binding.progressBar, binding.pagesTv);
                MyApplication.loadPdfSize("" + bookUrl, "" + bookTitle, binding.sizeTv);

                //set data
                binding.titleTv.setText(bookTitle);
                binding.descriptionTv.setText(description);
                binding.viewsTv.setText(viewsCount.replace("null", "N/A"));
                binding.downloadsTv.setText(downloadsCount.replace("null", "N/A"));
                binding.dateTv.setText(date);

            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {

            }
        });
    }


    private void checkIsFavorite() {
        //logged in check if its in favorite list or not
        DatabaseReference reference = FirebaseDatabase.getInstance().getReference("Users");
        reference.child(firebaseAuth.getUid()).child("Favorites").child(bookId).addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                isInMyFavorite = snapshot.exists(); //true: if exists, false if not exists
                if (isInMyFavorite) {
                    //exists in favoirte
                    binding.favoriteBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_cart_white, 0, 0);
                    binding.favoriteBtn.setText("Remove From Cart");
                } else {
                    //not exists in favorite
                    binding.favoriteBtn.setCompoundDrawablesRelativeWithIntrinsicBounds(0, R.drawable.ic_cart_white, 0, 0);
                    binding.favoriteBtn.setText("Add To Cart");
                }

            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {

            }
        });

    }
    public void showNotification() {
        Intent intent = new Intent(this, PdfDetailActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Book Added to Favorites")
                    .setContentText("A new book has been added to your favorites.")
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);
        }
        Notification notification;
        notification = builder.build();
        notificationManager.notify(getNotificationID(), notification);
    }
    public void showRemoveNotification() {
        Intent intent = new Intent(this, PdfDetailActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("Book Removed from Favorites")
                    .setContentText("A book has been removed from your favorites.")
                    .setPriority(Notification.PRIORITY_DEFAULT)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true);
        }
        Notification notification;
        notification = builder.build();
        notificationManager.notify(getNotificationID(), notification);
    }

    public int getNotificationID() {
        return (int) new Date().getTime();
    }

    public void createNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence channelName = "My Channel";
            String channelDiscription = "My Channel Description";
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, channelName, importance);
            channel.setDescription(channelDiscription);
            notificationManager.createNotificationChannel(channel);
        }
    }
}