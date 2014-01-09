/**
 * RxDroid - A Medication Reminder
 * Copyright (C) 2011-2014 Joseph Lehner <joseph.c.lehner@gmail.com>
 *
 *
 * RxDroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * RxDroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with RxDroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 *
 */

package at.jclehner.rxdroid;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.BigTextStyle;
import android.text.Html;
import android.util.Log;
import at.jclehner.androidutils.EventDispatcher;
import at.jclehner.rxdroid.Settings.DoseTimeInfo;
import at.jclehner.rxdroid.db.Database;
import at.jclehner.rxdroid.db.Drug;
import at.jclehner.rxdroid.db.Entries;
import at.jclehner.rxdroid.db.Schedule;
import at.jclehner.rxdroid.preferences.TimePeriodPreference.TimePeriod;
import at.jclehner.rxdroid.util.DateTime;
import at.jclehner.rxdroid.util.Millis;
import at.jclehner.rxdroid.util.Util;

public class NotificationReceiver extends BroadcastReceiver
{
	private static final String TAG = NotificationReceiver.class.getSimpleName();
	private static final boolean LOGV = BuildConfig.DEBUG;

	private static final int LED_CYCLE_MS = 5000;
	private static final int LED_ON_MS = 500;
	private static final int LED_OFF_MS = LED_CYCLE_MS - LED_ON_MS;

	private static final Class<?>[] EVENT_HANDLER_ARG_TYPES = { Date.class, int.class };

	public interface OnDoseTimeChangeListener
	{
		void onDoseTimeBegin(Date date, int doseTime);
		void onDoseTimeEnd(Date date, int doseTime);
	}

	static final String EXTRA_SILENT = "at.jclehner.rxdroid.extra.SILENT";
	static final String EXTRA_DATE = "at.jclehner.rxdroid.extra.DATE";
	static final String EXTRA_DOSE_TIME = "at.jclehner.rxdroid.extra.DOSE_TIME";
	static final String EXTRA_IS_DOSE_TIME_END = "at.jclehner.rxdroid.extra.IS_DOSE_TIME_END";
	static final String EXTRA_IS_ALARM_REPETITION = "at.jclehner.rxdroid.extra.IS_ALARM_REPETITION";
	static final String EXTRA_FORCE_UPDATE = "at.jclehner.rxdroid.extra.FORCE_UPDATE";
	static final String EXTRA_DRUG_ID_LIST = "drug_id_list";

	private static final String ACTION_MARK_ALL_AS_TAKEN = "at.jclehner.rxdroid.ACTION_MARK_ALL_AS_TAKEN";

	/**
	 * Refill-reminder snooze concept:
	 *
	 * - If displaying *only* the refill reminder (no dose notifications),
	 *   add an action to "remind tomorrow".
	 * - If the action is clicked, store the date that is "tomorrow", and
	 *   update notifications.
	 * - When updating notifications, check the date against the stored date
	 *   and skip the refill reminder if applicable.
	 * - For now (and simplicity's sake) clear that date when changing system
	 *   date/time/timezone).
	 *
	 */
	private static final String ACTION_SNOOZE_REFILL_REMINDER = "snooze";
	// Drugs affected by the refill reminder's snoozing
	private static final String REFILL_REMINDER_SNOOZE_DRUGS = "refill_reminder_snooze_drugs";

	private static final int NOTIFICATION_NORMAL = 0;
	private static final int NOTIFICATION_FORCE_UPDATE = 1;
	private static final int NOTIFICATION_FORCE_SILENT = 2;

	private Context mContext;
	private AlarmManager mAlarmMgr;

	private List<Drug> mAllDrugs;

	private boolean mDoPostSilent = false;
	private boolean mForceUpdate = false;

	private static final EventDispatcher<OnDoseTimeChangeListener> sEventMgr =
			new EventDispatcher<OnDoseTimeChangeListener>();

	public static void registerOnDoseTimeChangeListener(OnDoseTimeChangeListener l) {
		sEventMgr.register(l);
	}

	public static void unregisterOnDoseTimeChangeListener(OnDoseTimeChangeListener l) {
		sEventMgr.unregister(l);
	}

	@Override
	public void onReceive(Context context, Intent intent)
	{
		if(intent == null)
			return;

		Settings.init();
		Database.init();

		mContext = context;
		mAlarmMgr = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		mDoPostSilent = intent.getBooleanExtra(EXTRA_SILENT, false);
		mAllDrugs = Database.getAll(Drug.class);

		if(ACTION_MARK_ALL_AS_TAKEN.equals(intent.getAction()))
		{
			Entries.markAllNotifiedDosesAsTaken(0);
			//
		}
		else if(ACTION_SNOOZE_REFILL_REMINDER.equals(intent.getAction()))
		{
			final Date tomorrow = DateTime.add(DateTime.today(), Calendar.DAY_OF_MONTH, 1);
			Settings.putDate(Settings.Keys.NEXT_REFILL_REMINDER_DATE, tomorrow);
		}
		else
		{
			final boolean isAlarmRepetition = intent.getBooleanExtra(EXTRA_IS_ALARM_REPETITION, false);

			final int doseTime = intent.getIntExtra(EXTRA_DOSE_TIME, Schedule.TIME_INVALID);
			if(doseTime != Schedule.TIME_INVALID)
			{
				if(!isAlarmRepetition)
				{
					final Date date = (Date) intent.getSerializableExtra(EXTRA_DATE);
					final boolean isDoseTimeEnd = intent.getBooleanExtra(EXTRA_IS_DOSE_TIME_END, false);
					final String eventName = isDoseTimeEnd ? "onDoseTimeEnd" : "onDoseTimeBegin";

					sEventMgr.post(eventName, EVENT_HANDLER_ARG_TYPES, date, doseTime);
				}
			}

			mForceUpdate = isAlarmRepetition ? true : intent.getBooleanExtra(EXTRA_FORCE_UPDATE, false);
			rescheduleAlarms();
		}

		updateCurrentNotifications();
	}

	private void rescheduleAlarms()
	{
		cancelAllAlarms();
		scheduleNextAlarms();
	}

	private void scheduleNextAlarms()
	{
		if(Settings.getDoseTimeBegin(Drug.TIME_MORNING) == null)
		{
			Log.w(TAG, "No dose-time settings available. Not scheduling alarms.");
			return;
		}

		if(LOGV) Log.i(TAG, "Scheduling next alarms...");

		final DoseTimeInfo dtInfo = Settings.getDoseTimeInfo();

		if(dtInfo.activeDoseTime() != Schedule.TIME_INVALID)
			scheduleNextBeginOrEndAlarm(dtInfo, true);
		else
			scheduleNextBeginOrEndAlarm(dtInfo, false);
	}

	private void updateCurrentNotifications()
	{
		final DoseTimeInfo dtInfo = Settings.getDoseTimeInfo();
		final boolean isActiveDoseTime;

		Date date = dtInfo.activeDate();
		int doseTime = dtInfo.activeDoseTime();

		if(doseTime == Schedule.TIME_INVALID)
		{
			isActiveDoseTime = false;
			doseTime = dtInfo.nextDoseTime();
			date = dtInfo.nextDoseTimeDate();
		}
		else
			isActiveDoseTime = true;

		final int mode;

		if(mForceUpdate)
			mode = NOTIFICATION_FORCE_UPDATE;
		else if(mDoPostSilent)
			mode = NOTIFICATION_FORCE_SILENT;
		else
			mode = NOTIFICATION_NORMAL;

		updateNotification(date, doseTime, isActiveDoseTime, mode);
	}

	private void scheduleNextBeginOrEndAlarm(DoseTimeInfo dtInfo, boolean scheduleEnd)
	{
		final int doseTime = scheduleEnd ? dtInfo.activeDoseTime() : dtInfo.nextDoseTime();
		final Calendar time = dtInfo.currentTime();
		final Date doseTimeDate = scheduleEnd ? dtInfo.activeDate() : dtInfo.nextDoseTimeDate();

		final Bundle alarmExtras = new Bundle();
		alarmExtras.putSerializable(EXTRA_DATE, doseTimeDate);
		alarmExtras.putInt(EXTRA_DOSE_TIME, doseTime);
		alarmExtras.putBoolean(EXTRA_IS_DOSE_TIME_END, scheduleEnd);
		alarmExtras.putBoolean(EXTRA_SILENT, false);

		long offset;

		if(scheduleEnd)
			offset = Settings.getMillisUntilDoseTimeEnd(time, doseTime);
		else
			offset = Settings.getMillisUntilDoseTimeBegin(time, doseTime);

		long triggerAtMillis = time.getTimeInMillis() + offset;

		final int alarmRepeatMins = Settings.getStringAsInt(Settings.Keys.ALARM_REPEAT, 0);
		final long alarmRepeatMillis = alarmRepeatMins == -1 ? Millis.seconds(10) : Millis.minutes(alarmRepeatMins);

		if(alarmRepeatMillis > 0)
		{
			alarmExtras.putBoolean(EXTRA_FORCE_UPDATE, true);

			final long base = dtInfo.activeDate().getTime();
			int i = 0;

			while(base + (i * alarmRepeatMillis) < time.getTimeInMillis())
				++i;

			// We must tell the receiver whether the alarm is an actual dose time's
			// end or begin, or merely a repetition.

			final long triggerAtMillisWithRepeatedAlarm = base + i * alarmRepeatMillis;
			if(triggerAtMillisWithRepeatedAlarm < triggerAtMillis)
			{
				triggerAtMillis = triggerAtMillisWithRepeatedAlarm;
				alarmExtras.putBoolean(EXTRA_IS_ALARM_REPETITION, true);
			}

			//triggerAtMillis = base + (i * alarmRepeatMillis);
		}

		final long triggerDiffFromNow = triggerAtMillis - System.currentTimeMillis();
		if(triggerDiffFromNow < 0)
		{
			if(triggerDiffFromNow < Millis.seconds(-5))
				Log.w(TAG, "Alarm time is in the past by less than 5 seconds.");
			else
			{
				Log.w(TAG, "Alarm time is in the past. Ignoring...");
				return;
			}
		}

		if(alarmExtras.getBoolean(EXTRA_IS_ALARM_REPETITION))
			Log.i(TAG, "Scheduling next alarm for " + DateTime.toString(triggerAtMillis));
		else
		{
			Log.i(TAG, "Scheduling " + (scheduleEnd ? "end" : "begin") + " of doseTime " +
					doseTime + " on date " + DateTime.toDateString(doseTimeDate) + " for " +
					DateTime.toString(triggerAtMillis));
		}

		Log.i(TAG, "Alarm will go off in " + Util.millis(triggerDiffFromNow));

		setAlarm(triggerAtMillis, createOperation(alarmExtras));
	}

	private void setAlarm(long triggerAtMillis, PendingIntent operation)
	{
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
			mAlarmMgr.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
		else
			mAlarmMgr.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation);
	}

	private void cancelAllAlarms() {
		mAlarmMgr.cancel(createOperation(null));
	}

	private PendingIntent createOperation(Bundle extras)
	{
		Intent intent = new Intent(mContext, NotificationReceiver.class);
		intent.setAction(Intent.ACTION_MAIN);

		if(extras != null)
			intent.putExtras(extras);

		return PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
	}

	private PendingIntent createDrugListIntent(Date date)
	{
		final Intent intent = new Intent(mContext, DrugListActivity.class);
		intent.putExtra(DrugListActivity.EXTRA_STARTED_FROM_NOTIFICATION, true);
		intent.putExtra(DrugListActivity.EXTRA_DATE, date);
		intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

		return PendingIntent.getActivity(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);
	}

	public void updateNotification(Date date, int doseTime, boolean isActiveDoseTime, int mode)
	{
		final List<Drug> drugsWithLowSupplies = new ArrayList<Drug>();
		final int lowSupplyDrugCount = getDrugsWithLowSupplies(date, doseTime, drugsWithLowSupplies);
		final int missedDoseCount = getDrugsWithMissedDoses(date, doseTime, isActiveDoseTime);
		final int dueDoseCount = isActiveDoseTime ? getDrugsWithDueDoses(date, doseTime) : 0;

		int titleResId = R.string._title_notification_doses;
		int icon = R.drawable.ic_stat_normal;

		final StringBuilder sb = new StringBuilder();
		final String[] lines = new String[2];

		int lineCount = 0;

		if(missedDoseCount != 0 || dueDoseCount != 0)
		{
			if(dueDoseCount != 0)
				sb.append(RxDroid.getQuantityString(R.plurals._qmsg_due, dueDoseCount));

			if(missedDoseCount != 0)
			{
				if(sb.length() != 0)
					sb.append(", ");

				sb.append(RxDroid.getQuantityString(R.plurals._qmsg_missed, missedDoseCount));
			}

			lines[1] = "<b>" + getString(R.string._title_notification_doses) + "</b> " + Util.escapeHtml(sb.toString());
		}

		final boolean isShowingLowSupplyNotificationOnly;

		if(lowSupplyDrugCount != 0)
		{
			final String msg;
			final String first = Entries.getDrugName(drugsWithLowSupplies.get(0));

			icon = R.drawable.ic_stat_exclamation;
			isShowingLowSupplyNotificationOnly = sb.length() == 0;
			//titleResId = R.string._title_notification_low_supplies;

			if(lowSupplyDrugCount == 1)
				msg = getString(R.string._qmsg_low_supply_single, first);
			else
			{
				final String second = Entries.getDrugName(drugsWithLowSupplies.get(1));
				msg = RxDroid.getQuantityString(R.plurals._qmsg_low_supply_multiple, lowSupplyDrugCount - 1, first, second);
			}

			if(isShowingLowSupplyNotificationOnly)
			{
				sb.append(msg);
				titleResId = R.string._title_notification_low_supplies;
			}

			lines[0] = "<b>" + getString(R.string._title_notification_low_supplies) + "</b> " + Util.escapeHtml(msg);
		}
		else
			isShowingLowSupplyNotificationOnly = false;

		final int priority;
		if(isShowingLowSupplyNotificationOnly)
			priority = NotificationCompat.PRIORITY_DEFAULT;
		else
			priority = NotificationCompat.PRIORITY_HIGH;

		final String message = sb.toString();
		if(message.length() == 0 || isShowingLowSupplyNotificationOnly)
		{
			final boolean cancelNotification;
			if(isShowingLowSupplyNotificationOnly)
			{
				final Date today = DateTime.today();
				final Date nextRefillReminderDate = Settings.getDate(Settings.Keys.NEXT_REFILL_REMINDER_DATE);

				Log.d(TAG, "Showing refill reminder only; nextRefillReminderDate=" + nextRefillReminderDate);

				cancelNotification = nextRefillReminderDate != null && today.before(nextRefillReminderDate);

				if(cancelNotification)
					Log.d(TAG, "  date is in the future; will cancel notification");
				else if(nextRefillReminderDate != null)
				{
					// We have a reminder date, but it's already in the past. Clear it.
					Settings.putDate(Settings.Keys.NEXT_REFILL_REMINDER_DATE, null);
					Log.d(TAG, "  date is in the past; setting to null");
				}
			}
			else
				cancelNotification = true;

			if(cancelNotification)
			{
				getNotificationManager().cancel(R.id.notification);
				return;
			}
		}

		final StringBuilder source = new StringBuilder();

//		final InboxStyle inboxStyle = new InboxStyle();
//		inboxStyle.setBigContentTitle(getString(R.string.app_name) +
//				" (" + (dueDoseCount + missedDoseCount + lowSupplyDrugCount) + ")");

		for(String line : lines)
		{
			if(line != null)
			{
				if(lineCount != 0)
					source.append("\n<br/>\n");

				source.append(line);
//				inboxStyle.addLine(Html.fromHtml(line));
				++lineCount;
			}
		}

		final NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext);
		builder.setContentTitle(getString(titleResId));
		builder.setContentIntent(createDrugListIntent(date));
		builder.setContentText(message);
		builder.setTicker(getString(R.string._msg_new_notification));
		builder.setSmallIcon(icon);
		builder.setOngoing(true);
		builder.setUsesChronometer(false);
		builder.setWhen(0);
		builder.setPriority(priority);

		// TODO setOngoing(false), if requested by the user

		if(lineCount > 1)
		{
			final BigTextStyle style = new BigTextStyle();
			style.setBigContentTitle(getString(R.string.app_name));
			style.bigText(Html.fromHtml(source.toString()));
			builder.setStyle(style);
		}

		if(!isShowingLowSupplyNotificationOnly && !Settings.getBoolean(Settings.Keys.USE_SAFE_MODE, false))
		{
			Intent intent = new Intent(mContext, NotificationReceiver.class);
			intent.setAction(ACTION_MARK_ALL_AS_TAKEN);

			PendingIntent operation = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

			builder.addAction(R.drawable.ic_action_tick, getString(R.string._title_take_all_doses), operation);
		}
		else if(isShowingLowSupplyNotificationOnly)
		{
			final Set<String> drugIds = new HashSet<String>();







			Intent intent = new Intent(mContext, NotificationReceiver.class);
			intent.setAction(ACTION_SNOOZE_REFILL_REMINDER);

			PendingIntent operation = PendingIntent.getBroadcast(mContext, 0, intent, PendingIntent.FLAG_CANCEL_CURRENT);

			if(Version.SDK_IS_JELLYBEAN_OR_NEWER)
				builder.addAction(R.drawable.ic_action_snooze, getString(R.string._title_remind_tomorrow), operation);
			else
			{
				builder.setDeleteIntent(operation);
				// Technically it's ongoing, but you cannot delete ongoing notifications
				builder.setOngoing(false);
			}
		}

		final int currentHash = message.hashCode();
		final int lastHash = Settings.getInt(Settings.Keys.LAST_MSG_HASH);

		if(mode == NOTIFICATION_FORCE_UPDATE || currentHash != lastHash)
		{
			builder.setOnlyAlertOnce(false);
			Settings.putInt(Settings.Keys.LAST_MSG_HASH, currentHash);
		}
		else
			builder.setOnlyAlertOnce(true);

		// Prevents low supplies from constantly annoying the user with
		// notification's sound and/or vibration if alarms are repeated.
		if(isShowingLowSupplyNotificationOnly)
			mode = NOTIFICATION_FORCE_SILENT;

		int defaults = 0;

		final String lightColor = Settings.getString(Settings.Keys.NOTIFICATION_LIGHT_COLOR, "");
		if(lightColor.length() == 0)
			defaults |= Notification.DEFAULT_LIGHTS;
		else
		{
			try
			{
				int ledARGB = Integer.parseInt(lightColor, 16);
				if(ledARGB != 0)
				{
					ledARGB |= 0xff000000; // set alpha to ff
					builder.setLights(ledARGB, LED_ON_MS, LED_OFF_MS);
				}
			}
			catch(NumberFormatException e)
			{
				Log.e(TAG, "Failed to parse light color; using default", e);
				defaults |= Notification.DEFAULT_LIGHTS;
			}
		}

		if(mode != NOTIFICATION_FORCE_SILENT)
		{
			boolean isNowWithinQuietHours = false;

			do
			{
				if(!Settings.isChecked(Settings.Keys.QUIET_HOURS, false))
					break;

				final String quietHoursStr = Settings.getString(Settings.Keys.QUIET_HOURS);
				if(quietHoursStr == null)
					break;

				final TimePeriod quietHours = TimePeriod.fromString(quietHoursStr);
				if(quietHours.contains(DumbTime.now()))
					isNowWithinQuietHours = true;

			} while(false);

			if(!isNowWithinQuietHours)
			{
				final String ringtone = Settings.getString(Settings.Keys.NOTIFICATION_SOUND);
				if(ringtone != null)
					builder.setSound(Uri.parse(ringtone));
				else
					defaults |= Notification.DEFAULT_SOUND;

				if(LOGV) Log.i(TAG, "Sound: " + (ringtone != null ? ringtone.toString() : "(default)"));
			}
			else
				Log.i(TAG, "Currently within quiet hours; muting sound...");
		}

		if(mode != NOTIFICATION_FORCE_SILENT && Settings.getBoolean(Settings.Keys.USE_VIBRATOR, true))
			defaults |= Notification.DEFAULT_VIBRATE;

		builder.setDefaults(defaults);

		getNotificationManager().notify(R.id.notification, builder.build());
	}

	private  int getDrugsWithDueDoses(Date date, int doseTime) {
		return Entries.getDrugsWithDueDoses(mAllDrugs, date, doseTime, null);
	}

	private int getDrugsWithMissedDoses(Date date, int activeOrNextDoseTime, boolean isActiveDoseTime) {
		return Entries.getDrugsWithMissedDoses(mAllDrugs, date, activeOrNextDoseTime, isActiveDoseTime, null);
	}

	private int getDrugsWithLowSupplies(Date date, int doseTime, List<Drug> outDrugs)
	{
		int count = 0;

		for(Drug drug : mAllDrugs)
		{
			if(Entries.hasLowSupplies(drug, date))
			{
				++count;

				if(outDrugs != null)
					outDrugs.add(drug);
			}
		}

		return count;
	}

	private String getString(int resId, Object... formatArgs) {
		return mContext.getString(resId, formatArgs);
	}

	private NotificationManager getNotificationManager() {
		return (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	/* package */ static void cancelNotifications()
	{
		final NotificationManager nm = (NotificationManager) RxDroid.getContext()
				.getSystemService(Context.NOTIFICATION_SERVICE);

		nm.cancel(R.id.notification);
	}

	/* package */ static void rescheduleAlarmsAndUpdateNotification(boolean silent) {
		rescheduleAlarmsAndUpdateNotification(null, silent);
	}

	/* package */ static void rescheduleAlarmsAndUpdateNotification(boolean silent, boolean forceUpdate) {
		rescheduleAlarmsAndUpdateNotification(null, silent, forceUpdate);
	}

	/* package */ static void rescheduleAlarmsAndUpdateNotification(Context context, boolean silent) {
		rescheduleAlarmsAndUpdateNotification(context, silent, false);
	}

	/* package */ static void rescheduleAlarmsAndUpdateNotification(Context context, boolean silent, boolean forceUpdate)
	{
		if(context == null)
			context = RxDroid.getContext();
		final Intent intent = new Intent(context, NotificationReceiver.class);
		intent.setAction(Intent.ACTION_MAIN);
		intent.putExtra(EXTRA_SILENT, silent);
		intent.putExtra(EXTRA_FORCE_UPDATE, forceUpdate);
		context.sendBroadcast(intent);
	}
}
