/**
 * RxDroid - A Medication Reminder
 * Copyright (C) 2011-2014 Joseph Lehner <joseph.c.lehner@gmail.com>
 *
 *
 * RxDroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version. Additional terms apply (see LICENSE).
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

package at.jclehner.rxdroid.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

import at.jclehner.rxdroid.DrugListActivity;
import at.jclehner.rxdroid.R;
import at.jclehner.rxdroid.Settings;
import at.jclehner.rxdroid.Settings.Keys;
import at.jclehner.rxdroid.Theme;
import at.jclehner.rxdroid.Version;

import com.mobeta.android.dslv.DragSortController;
import com.mobeta.android.dslv.DragSortListView;

public class AutoDragSortListView extends DragSortListView
{
	public interface OnOrderChangedListener
	{
		void onOrderChanged();
	}

	@SuppressWarnings("rawtypes")
	private ArrayAdapter mAdapter;
	private DragSortController mController;
	private OnOrderChangedListener mOnOrderChangedListener;

	private View mDraggedChild;
	private Drawable mDraggedChildBackground;

	public AutoDragSortListView(Context context, AttributeSet attrs)
	{
		super(context, attrs);

		if(isInEditMode())
			return;

		final int alpha = (Theme.isDark() ? 0xc0 : 0x40) << 24;
		final int colorId = Theme.isDark() ? R.color.active_text_light : R.color.active_text_dark;
		// first remove the alpha via XOR, then OR the new alpha back in
		final int color = (getResources().getColor(colorId) ^ 0xff000000) | alpha;

		mController = new DragSortController(this) {

			@Override
			public View onCreateFloatView(int position)
			{
				if(Version.SDK_IS_PRE_HONEYCOMB)
				{
					mDraggedChild = getChildAt(position + getHeaderViewsCount() - getFirstVisiblePosition());

					if(mDraggedChild != null)
					{
						// On pre-Honeycomb, the ListView items appear to have a background set
						//v.setBackgroundResource(0);
						mDraggedChildBackground = mDraggedChild.getBackground();
						mDraggedChild.setBackgroundColor(color);
					}
				}

				return super.onCreateFloatView(position);
			}

			@SuppressWarnings("deprecation")
			@Override
			public void onDestroyFloatView(View floatView)
			{
				if(Version.SDK_IS_PRE_HONEYCOMB)
				{
					mDraggedChild.setBackgroundDrawable(mDraggedChildBackground);
					mDraggedChildBackground = null;
					mDraggedChild = null;
				}

				super.onDestroyFloatView(floatView);
			}

		};

		mController.setDragInitMode(DragSortController.ON_DOWN);
		mController.setSortEnabled(isSmartSortDisabled());

		mController.setBackgroundColor(color);

		setOnTouchListener(mController);
		setFloatViewManager(mController);
		setDropListener(mDropListener);

		Settings.registerOnChangeListener(mPrefListener);
	}

	public void setDragHandleId(int id)
	{
		mController.setDragHandleId(id);
		setDragEnabled(isSmartSortDisabled());
	}

	public void setOnOrderChangedListener(OnOrderChangedListener l) {
		mOnOrderChangedListener = l;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public void setAdapter(ListAdapter adapter)
	{
		if(adapter instanceof ArrayAdapter)
		{
			mAdapter = (ArrayAdapter) adapter;
			super.setAdapter(mAdapter);
		}
		else
			throw new IllegalArgumentException("Only ArrayAdapters are supported");
	}

	private boolean isSmartSortDisabled() {
		return !Settings.getBoolean(Keys.USE_SMART_SORT, true);
	}

	private final OnSharedPreferenceChangeListener mPrefListener = new OnSharedPreferenceChangeListener()
	{
		@Override
		public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key)
		{
			if(Keys.USE_SMART_SORT.equals(key))
			{
				setDragEnabled(sharedPreferences.getBoolean(key, true));
				mController.setSortEnabled(isSmartSortDisabled());
			}
		}
	};

	private final DropListener mDropListener = new DropListener() {

		@SuppressWarnings("unchecked")
		@Override
		public void drop(int from, int to)
		{
			Object item = mAdapter.getItem(from);
			mAdapter.remove(item);
			mAdapter.insert(item, to);
			mAdapter.notifyDataSetChanged();

			if(mOnOrderChangedListener != null)
				mOnOrderChangedListener.onOrderChanged();
		}
	};
}
