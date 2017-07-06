/*
 * (c) Copyright 2017 Mopria Alliance, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mopria.FilePassthrough;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJob;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.widget.Toast;
import android.Manifest;

import org.mopria.PDFPassthrough.R;

/**
 * An activity to render different document format for print
 */
public class FilePassthroughActivity extends Activity{
    private static final String TAG = "FilePassthrough";

    private final String TEMP_DIR = "tmp";
    private final int MY_PERMISSIONS_READ_EXTERNAL_STORAGE = 101;
    public static final String SHARE_INTENT = "share";
    public static final String OPEN_INTENT = "open";

    public static final String MPS_ACTION_FILE_PASS_THROUGH = "org.mopria.printplugin.FILE_PASS_THROUGH";
    public static final String MPS_FILE_URI = "org.mopria.printplugin.FILE_URI";
    public static final String MPS_FILE_MIME = "org.mopria.printplugin.FILE_MIME";
    public static final String MPS_FILE_JOB_ID = "org.mopria.printplugin.FILE_JOB_ID";
    public static final String MPS_PAGE_RANGE = "org.mopria.printplugin.PAGE_RANGE";

    private File mWorkFile;
    private String mJobName;
    private String mDocMimeType;
    private String mPrintJobId;
    private String mExtension;
    private int mTotalPages = 0;
    private boolean mFileAccess = true;
    private ProgressDialog mDialog;
    private String mReferrer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent().getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            mReferrer = SHARE_INTENT + ", " + mReferrer;
        } else if (Intent.ACTION_VIEW.equals(action)) {
            mReferrer = OPEN_INTENT + ", " + mReferrer;
        }

        // Ask READ_EXTERNAL_STORAGE permission to the user
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            mFileAccess = false;
            int permission = ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE);
            if (permission != PackageManager.PERMISSION_GRANTED) {
                // We don't have permission so prompt the user
                ActivityCompat.requestPermissions(
                        this,
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                        MY_PERMISSIONS_READ_EXTERNAL_STORAGE
                );
                return;
            } else {
                mFileAccess = true;
            }
        }
        if (mFileAccess) {
            processAction();
        }
    }

    /**
     * Get the uri of the content
     * @return the content uri, like file uri
     */
    private Uri getContentUri() {
        Uri contentUri = null;
        String action = getIntent().getAction();
        Log.d(TAG, "getContentUri action = " + action);

        if (Intent.ACTION_SEND.equals(action)) {
            contentUri = (Uri) getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_VIEW.equals(action)) {
            contentUri = getIntent().getData();
        }

        return contentUri;
    }

    /**
     * Process Action Send
     */
    private void processAction() {
        mDocMimeType = getIntent().resolveType(this);
        if (mDocMimeType == null) {
            finishError(R.string.mopria_unsupported_document_type);
            return;
        }
        Log.i(TAG,"mDocMimeType " + mDocMimeType);
        // get a file Uri from actions
        Uri uri = getContentUri();
        Log.i(TAG,"File Uri = " + uri );
        if (uri == null) {
            finishError(R.string.mopria_document_reading_error);
            return;
        }
        mWorkFile = convertToFile(uri);
        // Re-attempt to get the mime type from the extension if it wasn't found already
        if (TextUtils.isEmpty(mDocMimeType)) {
            mDocMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(
                    mWorkFile.getName()));
        }
        saveWorkFile(uri);
        // Use file name for "job-name"
        mJobName = mWorkFile.getName();
        if (mWorkFile != null) {
            printDocument();
        }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String permissions[], int[] grantResults) {
        switch (requestCode) {
            case MY_PERMISSIONS_READ_EXTERNAL_STORAGE: {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.length > 0
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    mFileAccess = true;
                   Log.d(TAG,"External Storage Read Permission Granted");
                    processAction();
                } else {
                    mFileAccess = false;
                    String error = "External Storage Read Permission denied";
                   Log.d(TAG,error);
                    finishAndRemoveTask();
                }
                return;
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (mDialog != null && mDialog.isShowing()) {
            mDialog.dismiss();
        }
        super.onDestroy();
    }

    /**
     * Finish with emitting an error message
     */
    private void finishError(int resId) {
        Toast.makeText(getApplicationContext(), resId, Toast.LENGTH_LONG).show();

        // Get error message in US English
        Configuration config = new Configuration(this.getResources().getConfiguration());
        config.setLocale(Locale.US);
        endActivity();
    }

    /**
     * Send files to Print
     */
    @SuppressLint("DefaultLocale")
    private void printDocument() {
       Log.d(TAG,"mPrintFile= " + mWorkFile);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mTotalPages = getPdfPageCount(mWorkFile);
        }
        if (mTotalPages >= 0) {
            print();
        } else {
            finishError(R.string.mopria_unsupported_document_type);
        }
    }

    /**
     * Return PDF page count
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static int getPdfPageCount(File pdfFile) {
        try (ParcelFileDescriptor fileDescriptor = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer pdfRenderer = new PdfRenderer(fileDescriptor)) {
            return pdfRenderer.getPageCount();
        } catch (IOException e) {
            Log.e(TAG, "getPdfPageCount error", e);
            return 0;
        } catch (SecurityException e) {
            Log.e(TAG, "getPdfPageCount error since the file requires a password or the security scheme is not supported", e);
            return 0;
        }
    }

    /**
     * Print to Print Service
     */
    private void print() {
        PrintTask task = new PrintTask(this);
        task.execute();
    }

    /**
     * Print manager to handle print job
     */
    private void printManagerPrint() {
        PrintManager printManager = (PrintManager) this.getSystemService(Context.PRINT_SERVICE);
        PrintJob printJob = printManager.print(mJobName, new LocalPrintDocumentAdapter(), null);
        mPrintJobId = printJob.getId().toString();
    }

    private class PrintTask extends AsyncTask<Void, Void, Void> {

        public PrintTask(FilePassthroughActivity activity) {
            mDialog = new ProgressDialog(activity);
        }

        @Override
        protected void onPreExecute() {
            String str = getString(R.string.mopria_print_document_progress);
            mDialog.setMessage(str);
            mDialog.show();
        }

        @Override
        protected void onPostExecute(Void result) {
            if (mDialog.isShowing()) {
                mDialog.dismiss();
            }
        }

        @Override
        protected Void doInBackground(Void... params) {
            printManagerPrint();
            return null;
        }
    }

    private File convertToFile(Uri uri) {
        String filename = uri.getLastPathSegment();
        mExtension = MimeTypeMap.getFileExtensionFromUrl(filename);
        if (TextUtils.isEmpty(mExtension)) {
            if (getContentResolver().SCHEME_CONTENT.equals(uri.getScheme())) {
                filename = getContentName(uri);
            }
            // get a new file extension
            mExtension = MimeTypeMap.getFileExtensionFromUrl(filename);
        }
        if (TextUtils.isEmpty(mDocMimeType)) {
            mDocMimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(mExtension);
        }

        File workPath = new File(getFilesDir(), TEMP_DIR);
        if (!workPath.exists()) {
            workPath.mkdir();
        }

        filename = filename.replace(File.separator, "_");

        return new File(workPath, filename);
    }

    private void saveWorkFile (Uri uri) {
        try {
            // Cleanup previous file if any
            if (mWorkFile.exists()) {
                mWorkFile.delete();
            }
            InputStream in = getContentResolver().openInputStream(uri);
            OutputStream out = new FileOutputStream(mWorkFile);
            copyStream(in, out);
        } catch (IOException e) {
            Log.e(TAG,"Could not save work file", e);
            finishError(R.string.mopria_document_reading_error);
            return;
        }
    }

    /**
     * Get file content name
     */
    private String getContentName(Uri uri) {
        Cursor cursor = null;
        String[] projection = { MediaStore.MediaColumns.DISPLAY_NAME };
        try {
            cursor = getContentResolver().query(uri, projection, null, null, null);
            if (cursor == null) {
                Log.w(TAG,"getContentName(): cursor is null");
                return null;
            }
            cursor.moveToFirst();
            return cursor.getString(0);
        } catch (Exception e) {
            Log.w(TAG,"getContentName() failed", e);
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * Copy a file from source file name to destination file name.
     */
    public static void copyStream(InputStream myInput, OutputStream myOutput) throws IOException {
        // transfer bytes from the inputfile to the outputfile
        byte[] buffer = new byte[2048];
        int length;
        while ((length = myInput.read(buffer)) > 0) {
            myOutput.write(buffer, 0, length);
        }
        // Close the streams
        myOutput.flush();
        myOutput.close();
        myInput.close();
    }

    private void endActivity() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    /**
     * A local print document adapter for PDF/Office document print
     */
    private class LocalPrintDocumentAdapter extends PrintDocumentAdapter {
        @Override
        public void onLayout(PrintAttributes oldAttributes,
                             PrintAttributes newAttributes,
                             CancellationSignal cancellationSignal,
                             LayoutResultCallback callback,
                             Bundle metadata) {
            if (cancellationSignal.isCanceled()) {
                callback.onLayoutCancelled();
                return;
            }
           Log.d(TAG,"Total page count is " + mTotalPages);

            PrintDocumentInfo.Builder builder = new PrintDocumentInfo
                    .Builder(mJobName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(mTotalPages);

            PrintDocumentInfo info = builder.build();
            callback.onLayoutFinished(info, true);
        }

        @Override
        public void onWrite(final PageRange[] pageRanges,
                            final ParcelFileDescriptor destination,
                            final CancellationSignal cancellationSignal,
                            final WriteResultCallback callback) {
            passThrough(pageRanges, destination, callback);
            callback.onWriteFinished(new PageRange[] { PageRange.ALL_PAGES });
        }

        private void passThrough(final PageRange[] pageRanges,
                                 final ParcelFileDescriptor destination,
                                 final WriteResultCallback callback) {
            FileInputStream inputStream = null;
            FileOutputStream outputStream = null;

            try {
                inputStream = new FileInputStream(mWorkFile);
                outputStream = new FileOutputStream(destination.getFileDescriptor());
                copyStream(inputStream, outputStream);
            } catch (IOException e) {
                callback.onWriteFailed(e.toString());
                return;
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (IOException e) {
                        callback.onWriteFailed(e.toString());
                        return;
                    }
                }
                if (outputStream != null) {
                    try {
                        // outputStream.flush();
                        outputStream.close();
                    } catch (IOException e) {
                        callback.onWriteFailed(e.toString());
                        return;
                    }
                }
            }

            /* Send File Pass Through Strings to Mopria Print Service */
            sendFilePassThroughString(pageRanges);
        }

        /**
         * Send page range string
         */
        private void sendFilePassThroughString(final PageRange[] pageRanges) {
            Uri fileUri = null;
            String pageRange = getPageRangeString(pageRanges);

            Intent intent = new Intent();
            intent.setAction(MPS_ACTION_FILE_PASS_THROUGH);

            try {
                fileUri = FileProvider.getUriForFile(FilePassthroughActivity.this, "org.mopria.FilePassthrough.fileprovider", mWorkFile);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "The selected file can't be shared: " + mWorkFile);
            }
            getApplicationContext().grantUriPermission("org.mopria.printplugin", fileUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Log.d(TAG, "The shared file Uri is " + fileUri);
            intent.putExtra(MPS_FILE_URI, fileUri.toString());

            // Grant temporary read permission to the content URI
            intent.putExtra(MPS_FILE_MIME, mDocMimeType);
            intent.putExtra(MPS_FILE_JOB_ID, mPrintJobId);
            intent.putExtra(MPS_PAGE_RANGE, pageRange);
            Log.d(TAG, "Page range is " + pageRange);
            sendBroadcast(intent);
        }

        /**
         * Get page range string
         */
        private String getPageRangeString(final PageRange[] pageRanges) {
            if (pageRanges != null && pageRanges.length > 0) {

                StringBuilder pageRangeBuilder = new StringBuilder();
                for (PageRange range : pageRanges) {
                    //Log.d(TAG, "getPageRangeString Page ranges =  " + range.toString());
                    if (range.equals(PageRange.ALL_PAGES)) {
                        return null;
                    }
                    int start = range.getStart() + 1;
                    int end = range.getEnd() + 1;

                    if (pageRangeBuilder.length() > 0) {
                        pageRangeBuilder.append(',');
                    }

                    if (start == end) {
                        pageRangeBuilder.append(String.valueOf(start));
                    } else if (start < end) {
                        pageRangeBuilder.append(String.valueOf(start));
                        pageRangeBuilder.append('-');
                        pageRangeBuilder.append(String.valueOf(end));
                    }
                }
                return pageRangeBuilder.toString();
            }
            return null;
        }

        /**
         * Called when printing finishes. You can use this callback to release
         * resources acquired in {@link #onStart()}. This method is invoked on
         * the main thread.
         */
        @Override
        public void onFinish() {
            endActivity();
            mWorkFile.delete();
            super.onFinish();
        }
    }
}
