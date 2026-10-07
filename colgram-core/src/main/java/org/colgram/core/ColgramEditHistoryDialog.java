package org.colgram.core;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ColgramEditHistoryDialog — Interactive viewer for message edit history.
 * Displays all prior revisions of an edited message with timestamps.
 */
public class ColgramEditHistoryDialog {

    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault());

    public static void show(Context context, long dialogId, int messageId) {
        if (context == null) return;

        List<ColgramDatabase.MessageEditEntry> history = ColgramDatabase.getInstance(context).getEditHistory(dialogId, messageId);
        if (history == null || history.isEmpty()) {
            new AlertDialog.Builder(context)
                .setTitle("История изменений")
                .setMessage("Предыдущих версий этого сообщения не найдено.")
                .setPositiveButton("OK", null)
                .show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("История правок (" + history.size() + ")");

        ListView listView = new ListView(context);
        listView.setDivider(null);
        listView.setPadding(32, 16, 32, 16);

        ArrayAdapter<ColgramDatabase.MessageEditEntry> adapter = new ArrayAdapter<ColgramDatabase.MessageEditEntry>(
                context, android.R.layout.simple_list_item_2, android.R.id.text1, history) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView text1 = view.findViewById(android.R.id.text1);
                TextView text2 = view.findViewById(android.R.id.text2);

                ColgramDatabase.MessageEditEntry entry = getItem(position);
                if (entry != null) {
                    text1.setText(entry.text);
                    text1.setTextSize(15);

                    String dateStr = DATE_FORMAT.format(new Date(entry.timestamp));
                    text2.setText("Редакция #" + (position + 1) + " — " + dateStr);
                    text2.setTextSize(12);
                    text2.setTextColor(0xFF888888);
                }
                return view;
            }
        };

        listView.setAdapter(adapter);
        builder.setView(listView);
        builder.setPositiveButton("Закрыть", null);
        builder.show();
    }
}
