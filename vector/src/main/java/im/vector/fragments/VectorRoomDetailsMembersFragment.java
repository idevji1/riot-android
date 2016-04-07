/*
 * Copyright 2016 OpenMarket Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.fragments;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.Fragment;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.TextView;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.data.RoomState;
import org.matrix.androidsdk.db.MXMediasCache;
import org.matrix.androidsdk.listeners.MXEventListener;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.callback.SimpleApiCallback;
import org.matrix.androidsdk.rest.model.Event;
import org.matrix.androidsdk.rest.model.MatrixError;
import im.vector.VectorApp;
import im.vector.R;
import im.vector.activity.CommonActivityUtils;
import im.vector.activity.MXCActionBarActivity;
import im.vector.activity.VectorMemberDetailsActivity;
import im.vector.activity.VectorRoomInviteMembersActivity;
import im.vector.adapters.ParticipantAdapterItem;
import im.vector.adapters.VectorRoomDetailsMembersAdapter;

import java.util.ArrayList;
import java.util.HashMap;

public class VectorRoomDetailsMembersFragment extends Fragment {
    private static final String LOG_TAG = "VectorRoomDetailsMembers";

    private static final int INVITE_USER_REQUEST_CODE = 777;
    private static final String CANCEL_SEARCH = null;
    private static final boolean REFRESH_FORCED = true;
    private static final boolean REFRESH_NOT_FORCED = false;

    // class members
    private MXSession mSession;
    private Room mRoom;

    // fragment items
    private View mProgressView;
    private VectorRoomDetailsMembersAdapter mAdapter;
    private ExpandableListView mParticipantsListView;
    private HashMap<Integer, Boolean> mIsListViewGroupExpandedMap;

    private boolean mIsMultiSelectionMode;
    private MenuItem mRemoveMembersMenuItem;
    private MenuItem mSwitchDeletionMenuItem;
    private boolean mIsKeyboardToHide = true;

    private final MXEventListener mEventListener = new MXEventListener() {
        @Override
        public void onLiveEvent(final Event event, RoomState roomState) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (Event.EVENT_TYPE_STATE_ROOM_MEMBER.equals(event.type) || Event.EVENT_TYPE_STATE_ROOM_THIRD_PARTY_INVITE.equals(event.type)) {
                        refreshRoomMembersList(mPatternValue, REFRESH_FORCED);
                    }
                }
            });
        }
    };

    private final VectorRoomDetailsMembersAdapter.OnRoomMembersSearchListener mSearchListener = new VectorRoomDetailsMembersAdapter.OnRoomMembersSearchListener() {
        @Override
        public void onSearchEnd(final int aSearchCountResult, final boolean aIsSearchPerformed) {
            mParticipantsListView.post(new Runnable() {
                @Override
                public void run () {
                    // stop waiting wheel
                    mProgressView.setVisibility(View.GONE);

                    if(mIsKeyboardToHide) {
                        // close IME after search to clean the UI
                        InputMethodManager inputMgr = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (null != inputMgr)
                            inputMgr.hideSoftInputFromWindow(mPatternToSearchEditText.getApplicationWindowToken(), 0);
                    }

                    // reset keyboard hiding to default value
                    mIsKeyboardToHide = true;

                    if(aIsSearchPerformed && (CANCEL_SEARCH != mPatternValue)) {
                        // only force expanding, if it is a genuine search (not a CANCEL_SEARCH)
                        forceListInExpandingState();

                        if (0 == aSearchCountResult) {
                            // no results found!
                            // TODO display "No results found" when count result = 0 ??
                        }
                    }
                }
            });
        }
    };

    // top view
    private View mViewHierarchy;
    private TextView mPatternToSearchEditText;
    private ImageView mClearSearchImageView;
    private String mPatternValue;

    public static VectorRoomDetailsMembersFragment newInstance() {
        return new VectorRoomDetailsMembersFragment();
    }

    @SuppressLint("LongLogTag")
    @Override
    public void onPause() {
        super.onPause();
        Log.d("RoomDetailsMembersFragment", "## onPause()");

        // sanity check
        if (null != mRoom) {
            mRoom.removeEventListener(mEventListener);
        }

        if (mIsMultiSelectionMode) {
            toggleMultiSelectionMode();
        }
    }

    @Override
    public void onStop() {
        InputMethodManager inputMgr = (InputMethodManager) getActivity().getSystemService(Context.INPUT_METHOD_SERVICE);
        if ((null != inputMgr) && (null != mPatternToSearchEditText))
            inputMgr.hideSoftInputFromWindow(mPatternToSearchEditText.getApplicationWindowToken(), 0);

        super.onStop();
    }

    @SuppressLint("LongLogTag")
    @Override
    public void onResume() {
        super.onResume();
        Log.d("RoomDetailsMembersFragment", "## onResume()");

        // sanity check
        if (null != mRoom) {
            mRoom.addEventListener(mEventListener);
        }

        // sanity check
        refreshRoomMembersList(mPatternValue, REFRESH_NOT_FORCED);

        // restore group expanding states
        updateListExpandingState();

        refreshMenuEntries();
    }

    @SuppressLint("LongLogTag")
    @Override
    public void onSaveInstanceState(Bundle aOutState) {
        super.onSaveInstanceState(aOutState);
        aOutState.putSerializable(CommonActivityUtils.KEY_GROUPS_EXPANDED_STATE, mIsListViewGroupExpandedMap);
        aOutState.putString(CommonActivityUtils.KEY_SEARCH_PATTERN, mPatternValue);
        Log.d("RoomDetailsMembersFragment", "## onSaveInstanceState()");
    }

    private void updateListExpandingState(){
        if(null !=  mParticipantsListView) {
            mParticipantsListView.post(new Runnable() {
                @Override
                public void run() {
                    int groupCount = mParticipantsListView.getExpandableListAdapter().getGroupCount();
                    Boolean isExpanded = CommonActivityUtils.GROUP_IS_EXPANDED;

                    for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {

                        if (null != mIsListViewGroupExpandedMap) {
                            isExpanded = mIsListViewGroupExpandedMap.get(Integer.valueOf(groupIndex));
                        }

                        if ((null == isExpanded) || (CommonActivityUtils.GROUP_IS_EXPANDED == isExpanded)) {
                            mParticipantsListView.expandGroup(groupIndex);
                        } else {
                            mParticipantsListView.collapseGroup(groupIndex);
                        }
                    }
                }
            });
        }
    }

    private void forceListInExpandingState(){
        if(null !=  mParticipantsListView) {
            mParticipantsListView.post(new Runnable() {
                @Override
                public void run() {
                    int groupCount = mParticipantsListView.getExpandableListAdapter().getGroupCount();

                    for (int groupIndex = 0; groupIndex < groupCount; groupIndex++) {
                        mParticipantsListView.expandGroup(groupIndex);
                    }
                }
            });
        }
    }

    @SuppressLint("LongLogTag")
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mViewHierarchy = inflater.inflate(R.layout.fragment_vector_add_participants, container, false);

        Activity activity = getActivity();

        if (activity instanceof MXCActionBarActivity) {
            MXCActionBarActivity anActivity = (MXCActionBarActivity) activity;
            mRoom = anActivity.getRoom();
            mSession = anActivity.getSession();

            finalizeInit();
        }

        // life cycle management
        if(null == savedInstanceState) {
            mIsListViewGroupExpandedMap = new HashMap<>();
            mPatternValue = CANCEL_SEARCH;
        } else {
            mIsListViewGroupExpandedMap = (HashMap<Integer, Boolean>) savedInstanceState.getSerializable(CommonActivityUtils.KEY_GROUPS_EXPANDED_STATE);
            mPatternValue = savedInstanceState.getString(CommonActivityUtils.KEY_SEARCH_PATTERN, CANCEL_SEARCH);

            if(null != mPatternValue)
                mPatternToSearchEditText.setText(mPatternValue);
        }

        setHasOptionsMenu(true);

        return mViewHierarchy;
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getActivity().getMenuInflater().inflate(R.menu.vector_room_details_add_people, menu);

        mRemoveMembersMenuItem = menu.findItem(R.id.ic_action_room_details_delete);
        mSwitchDeletionMenuItem = menu.findItem(R.id.ic_action_room_details_edition_mode);

        refreshMenuEntries();
    }

    /**
     * Trap the back key event.
     * @return true if the back key event is trapped.
     */
    public boolean onBackPressed() {
        if (mIsMultiSelectionMode) {
            toggleMultiSelectionMode();
            return true;
        }

        return false;
    }

    /**
     * Refresh the menu entries according to the edition mode
     */
    private void refreshMenuEntries() {
        if (null != mRemoveMembersMenuItem) {
            mRemoveMembersMenuItem.setVisible(mIsMultiSelectionMode);
        }

        if (null != mSwitchDeletionMenuItem) {
            mSwitchDeletionMenuItem.setVisible(!mIsMultiSelectionMode);
        }
    }

    /**
     * Update the activity title
     *
     * @param title
     */
    private void setActivityTitle(String title) {
        if (null != ((AppCompatActivity) getActivity()).getSupportActionBar()) {
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(title);
        }
    }

    /**
     * Reset the activity title.
     */
    private void resetActivityTitle() {
        mRemoveMembersMenuItem.setEnabled(true);
        mSwitchDeletionMenuItem.setEnabled(true);

        setActivityTitle(this.getResources().getString(R.string.room_details_title));
    }

    /**
     * Enable / disable the multiselection mode
     */
    private void toggleMultiSelectionMode() {
        resetActivityTitle();
        mIsMultiSelectionMode = !mIsMultiSelectionMode;
        mAdapter.setMultiSelectionMode(mIsMultiSelectionMode);
        refreshMenuEntries();
        mAdapter.notifyDataSetChanged();
    }

    /**
     * Kick an user Ids list
     * @param userIds the user ids list
     * @param index the start index
     */
    private void kickUsers(final  ArrayList<String> userIds, final int index) {
        if (index >= userIds.size()) {
            mProgressView.setVisibility(View.GONE);

            if (mIsMultiSelectionMode) {
                toggleMultiSelectionMode();
                resetActivityTitle();
            }
            return;
        }

        mRemoveMembersMenuItem.setEnabled(false);
        mSwitchDeletionMenuItem.setEnabled(false);

        mProgressView.setVisibility(View.VISIBLE);

        mRoom.kick(userIds.get(index), new ApiCallback<Void>() {
                    private void kickNext() {
                        kickUsers(userIds, index + 1);
                    }

                    @Override
                    public void onSuccess(Void info) {
                        kickNext();
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        kickNext();
                    }

                    @Override
                    public void onMatrixError(MatrixError e) {
                        kickNext();
                    }

                    @Override
                    public void onUnexpectedError(Exception e) {
                        kickNext();
                    }
                }

        );
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.ic_action_room_details_delete) {
            kickUsers(mAdapter.getSelectedUserIds(), 0);
        } else if (id ==  R.id.ic_action_room_details_edition_mode) {
            toggleMultiSelectionMode();
        }

        return super.onOptionsItemSelected(item);
    }

    /**
     * Finalize the fragment initialization.
     */
    private void finalizeInit() {
        MXMediasCache mxMediasCache = mSession.getMediasCache();

        View addMembersButton = mViewHierarchy.findViewById(R.id.add_participants_create_view);

        addMembersButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // pop to the home activity
                Intent intent = new Intent(getActivity(), VectorRoomInviteMembersActivity.class);
                intent.putExtra(VectorRoomInviteMembersActivity.EXTRA_MATRIX_ID, mSession.getMyUserId());
                intent.putExtra(VectorRoomInviteMembersActivity.EXTRA_ROOM_ID, mRoom.getRoomId());
                getActivity().startActivityForResult(intent, INVITE_USER_REQUEST_CODE);
            }
        });

        // search room members management
        mPatternToSearchEditText = (TextView)mViewHierarchy.findViewById(R.id.search_value_edit_text);
        mClearSearchImageView = (ImageView)mViewHierarchy.findViewById(R.id.clear_search_icon_image_view);

        // disable/enable search action according to search pattern
        mPatternToSearchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void afterTextChanged(android.text.Editable s) {
            }

            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String patternValue = mPatternToSearchEditText.getText().toString();

                if (TextUtils.isEmpty(patternValue)) {
                    // search input is empty: restore a not filtered room members list
                    mClearSearchImageView.setVisibility(View.INVISIBLE);
                    mPatternValue = CANCEL_SEARCH;
                    refreshRoomMembersList(mPatternValue, REFRESH_NOT_FORCED);
                } else {
                    mIsKeyboardToHide = false;
                    mClearSearchImageView.setVisibility(View.VISIBLE);
                    mPatternValue = mPatternToSearchEditText.getText().toString();
                    refreshRoomMembersList(mPatternValue, REFRESH_NOT_FORCED);
                }
            }
        });

        // add IME search action handler
        mPatternToSearchEditText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if ((actionId == EditorInfo.IME_ACTION_SEARCH) || (actionId == EditorInfo.IME_ACTION_GO) || (actionId == EditorInfo.IME_ACTION_DONE)) {
                    mPatternValue = mPatternToSearchEditText.getText().toString();
                    refreshRoomMembersList(mPatternValue, REFRESH_NOT_FORCED);
                    return true;
                }
                return false;
            }
        });

        mClearSearchImageView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                // clear search pattern to restore no filtered room members list
                mPatternToSearchEditText.setText("");
                mPatternValue = CANCEL_SEARCH;
                refreshRoomMembersList(mPatternValue, REFRESH_NOT_FORCED);
                forceListInExpandingState();
            }
        });

        mProgressView = mViewHierarchy.findViewById(R.id.add_participants_progress_view);
        mParticipantsListView = (ExpandableListView)mViewHierarchy.findViewById(R.id.room_details_members_exp_list_view);
        mAdapter = new VectorRoomDetailsMembersAdapter(getActivity(), R.layout.adapter_item_vector_add_participants, R.layout.adapter_item_vector_recent_header, mSession, (null != mRoom) ? mRoom.getRoomId() : null, false, mxMediasCache);
        mParticipantsListView.setAdapter(mAdapter);
        // the group indicator is managed in the adapter (group view creation)
        mParticipantsListView.setGroupIndicator(null);

        // set all the listener handlers called from the adapter
        mAdapter.setOnParticipantsListener(new VectorRoomDetailsMembersAdapter.OnParticipantsListener() {
            @Override
            public void onClick(final ParticipantAdapterItem participantItem) {
                Intent startRoomInfoIntent = new Intent(getActivity(), VectorMemberDetailsActivity.class);
                startRoomInfoIntent.putExtra(VectorMemberDetailsActivity.EXTRA_ROOM_ID, mRoom.getRoomId());
                startRoomInfoIntent.putExtra(VectorMemberDetailsActivity.EXTRA_MEMBER_ID, participantItem.mUserId);
                startRoomInfoIntent.putExtra(VectorMemberDetailsActivity.EXTRA_MATRIX_ID, mSession.getCredentials().userId);
                startActivity(startRoomInfoIntent);
            }

            @Override
            public void onSelectUserId(String userId) {
                ArrayList<String> userIds = mAdapter.getSelectedUserIds();

                if (0 != userIds.size()) {
                    setActivityTitle(userIds.size() + " " + getActivity().getResources().getString(R.string.room_details_selected));
                } else {
                    resetActivityTitle();
                }
            }

            @Override
            public void onRemoveClick(final ParticipantAdapterItem participantItem) {
                if (null == mRoom) {
                    mAdapter.removeParticipant(participantItem);
                } else {
                    String text = getActivity().getString(R.string.room_participants_remove_prompt_msg, participantItem.mDisplayName);

                    // The user is trying to leave with unsaved changes. Warn about that
                    new AlertDialog.Builder(VectorApp.getCurrentActivity())
                            .setTitle(R.string.room_participants_remove_prompt_title)
                            .setMessage(text)
                            .setPositiveButton(R.string.remove, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();

                                    ArrayList<String> userIds = new ArrayList<String>();
                                    userIds.add(participantItem.mUserId);

                                    kickUsers(userIds, 0);
                                }
                            })
                            .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                            .create()
                            .show();
                }
            }

            @Override
            public void onLeaveClick() {
                // The user is trying to leave with unsaved changes. Warn about that
                new AlertDialog.Builder(VectorApp.getCurrentActivity())
                        .setTitle(R.string.room_participants_leave_prompt_title)
                        .setMessage(getActivity().getString(R.string.room_participants_leave_prompt_msg))
                        .setPositiveButton(R.string.leave, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();

                                mProgressView.setVisibility(View.VISIBLE);

                                mRoom.leave(new ApiCallback<Void>() {
                                    @Override
                                    public void onSuccess(Void info) {
                                        mProgressView.setVisibility(View.GONE);
                                        // display something
                                    }

                                    @Override
                                    public void onNetworkError(Exception e) {
                                        mProgressView.setVisibility(View.GONE);
                                        // display something
                                    }

                                    @Override
                                    public void onMatrixError(MatrixError e) {
                                        mProgressView.setVisibility(View.GONE);
                                    }

                                    @Override
                                    public void onUnexpectedError(Exception e) {
                                        mProgressView.setVisibility(View.GONE);
                                    }
                                });
                                getActivity().finish();
                            }
                        })
                        .setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                dialog.dismiss();
                            }
                        })
                        .create()
                        .show();
            }

            @Override
            public void onGroupCollapsedNotif(int aGroupPosition){
                if(null != mIsListViewGroupExpandedMap) {
                    mIsListViewGroupExpandedMap.put(Integer.valueOf(aGroupPosition), CommonActivityUtils.GROUP_IS_COLLAPSED);
                }
            }

            @Override
            public void onGroupExpandedNotif(int aGroupPosition){
                if(null != mIsListViewGroupExpandedMap) {
                    mIsListViewGroupExpandedMap.put(Integer.valueOf(aGroupPosition), CommonActivityUtils.GROUP_IS_EXPANDED);
                }
            }
        });
    }


    /**
     * Perform a search request: the adapter is asked to filter the display according to
     * the search pattern.
     * The pattern to search is given in aSearchedPattern. To cancel the search, and display the
     * room members without any criteria, set aSearchedPattern to CANCEL_SEARCH.
     * @param aSearchedPattern string to be searched
     * @param aIsRefreshForced true to force a refresh whatever the pattern value
     */
    @SuppressLint("LongLogTag")
    private void refreshRoomMembersList(final String aSearchedPattern, boolean aIsRefreshForced) {
        if (null != mAdapter) {
            // start waiting wheel during the search
            mProgressView.setVisibility(View.VISIBLE);
            mAdapter.setSearchedPattern(aSearchedPattern, mSearchListener, aIsRefreshForced);
        } else {
            Log.w(LOG_TAG, "## refreshRoomMembersList(): search cannot be performed");
        }
    }

    /**
     * @return the participant User Ids except oneself.
     */
    public ArrayList<String> getUserIdsList() {
        return mAdapter.getUserIdsList();
    }

    /**
     * Activity result
     * @param requestCode the request code
     * @param resultCode teh result code
     * @param data the returned data
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if ((requestCode == INVITE_USER_REQUEST_CODE) && (resultCode == Activity.RESULT_OK)) {
            String userId = data.getStringExtra(VectorRoomInviteMembersActivity.EXTRA_SELECTED_USER_ID);

            if (null != userId) {
                mProgressView.setVisibility(View.VISIBLE);
                SimpleApiCallback<Void> callback = new SimpleApiCallback<Void>(getActivity()) {
                    @Override
                    public void onSuccess(Void info) {
                        mProgressView.setVisibility(View.GONE);
                    }

                    @Override
                    public void onNetworkError(Exception e) {
                        mProgressView.setVisibility(View.GONE);
                    }

                    @Override
                    public void onMatrixError(final MatrixError e) {
                        mProgressView.setVisibility(View.GONE);
                    }

                    @Override
                    public void onUnexpectedError(final Exception e) {
                        mProgressView.setVisibility(View.GONE);
                    }
                };

                if (android.util.Patterns.EMAIL_ADDRESS.matcher(userId).matches()) {
                    mRoom.inviteByEmail(userId, callback);
                } else {
                    ArrayList<String> userIDs = new ArrayList<String>();
                    userIDs.add(userId);
                    mRoom.invite(userIDs, callback);
                }
            }
        }
    }
}
