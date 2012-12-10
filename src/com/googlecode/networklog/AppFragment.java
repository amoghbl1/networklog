/* (C) 2012 Pragmatic Software
   This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/
 */

package com.googlecode.networklog;

import android.util.Log;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.ExpandableListView;
import android.widget.ExpandableListView.OnGroupExpandListener;
import android.widget.ExpandableListView.OnGroupCollapseListener;
import android.widget.ExpandableListView.OnChildClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.BaseExpandableListAdapter;
import android.widget.ImageView;
import android.widget.Filter;
import android.widget.Filterable;
import android.view.View;
import android.view.ViewGroup;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.graphics.drawable.Drawable;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.os.SystemClock;
import android.text.Html;
import android.text.Spanned;
import android.widget.TextView.BufferType;
import android.util.TypedValue;
import android.os.Parcelable;
import android.view.MenuItem;
import android.view.MenuInflater;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.ExpandableListView.ExpandableListContextMenuInfo;

/* newer API 11 clipboard unsupported on older APIs
import android.content.ClipboardManager;
import android.content.ClipData;
*/

/* use older clipboard API to support older devices */
import android.text.ClipboardManager;

import android.support.v4.app.Fragment;

import java.lang.StringBuilder;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;

public class AppFragment extends Fragment {
  // groupData bound to adapter, and filtered
  public ArrayList<GroupItem> groupData;
  // groupDataBuffer used to buffer incoming log entries and to hold original list data for filtering
  public ArrayList<GroupItem> groupDataBuffer;
  public boolean groupDataBufferIsDirty = false;
  private ExpandableListView listView;
  private CustomAdapter adapter;
  public Sort preSortBy;
  public Sort sortBy;
  public GroupItem cachedSearchItem;
  private ListViewUpdater updater;
  // remember last index return by getItemByAppUid to optimize-out call to binarySearch
  int lastGetItemByAppUidIndex = -1;
  private NetworkLog parent = null;
  private boolean gotInstalledApps = false;
  private boolean doNotRefresh = false;

  public class GroupItem {
    protected ApplicationsTracker.AppEntry app;
    protected long totalPackets;
    protected long totalBytes;
    protected long lastTimestamp;
    // childrenData bound to adapter, holds original list of children
    protected HashMap<String, ChildItem> childrenData;
    // holds filtered list of children
    // used in place of childrenData in getView, if non-empty
    protected HashMap<String, ChildItem> filteredChildItems;
    protected boolean childrenDataNeedsSort = false;
    protected boolean childrenAreFiltered = false;
    protected boolean childrenAreDirty = false;
    protected ArrayList<PacketGraphItem> packetGraphBuffer;
    protected boolean isExpanded = false;

    @Override
      public String toString() {
        return "(" + app.uidString + ") " + app.name;
      }
  }

  public class ChildItem {
    protected int sentPackets;
    protected int sentBytes;
    protected long sentTimestamp;
    protected int sentPort;
    protected String sentAddress;
    protected String out; // interface (rmnet, wifi, etc)

    protected int receivedPackets;
    protected int receivedBytes;
    protected long receivedTimestamp;
    protected int receivedPort;
    protected String receivedAddress;
    protected String in; // interface (rmnet, wifi, etc)

    protected ArrayList<PacketGraphItem> packetGraphBuffer;

    public String toString() {
      // todo: resolver here
      return sentAddress + ":" + sentPort + " -> " + receivedAddress + ":" + receivedPort;
    }
  }

  public void clear() {
    synchronized(groupData) {
      synchronized(groupDataBuffer) {
        for(GroupItem item : groupDataBuffer) {
          synchronized(item.childrenData) {
            List<String> list = new ArrayList<String>(item.childrenData.keySet());
            Iterator<String> itr = list.iterator();

            while(itr.hasNext()) {
              String host = itr.next();
              ChildItem childData = item.childrenData.get(host);
              childData.packetGraphBuffer.clear();
            }

            item.childrenData.clear();
            item.filteredChildItems.clear();
            item.childrenAreFiltered = false;
            item.packetGraphBuffer.clear();
          }
        }

        groupDataBuffer.clear();
        groupData.clear();
        groupDataBufferIsDirty = false;
      }
    }

    getInstalledApps();
    lastGetItemByAppUidIndex = -1;
  }

  protected static class SortAppsByBytes implements Comparator<GroupItem> {
    public int compare(GroupItem o1, GroupItem o2) {
      return o1.totalBytes > o2.totalBytes ? -1 : (o1.totalBytes == o2.totalBytes) ? 0 : 1;
    }
  }

  protected static class SortAppsByPackets implements Comparator<GroupItem> {
    public int compare(GroupItem o1, GroupItem o2) {
      return o1.totalPackets > o2.totalPackets ? -1 : (o1.totalPackets == o2.totalPackets) ? 0 : 1;
    }
  }

  protected static class SortAppsByTimestamp implements Comparator<GroupItem> {
    public int compare(GroupItem o1, GroupItem o2) {
      return o1.lastTimestamp > o2.lastTimestamp ? -1 : (o1.lastTimestamp == o2.lastTimestamp) ? 0 : 1;
    }
  }

  protected static class SortAppsByName implements Comparator<GroupItem> {
    public int compare(GroupItem o1, GroupItem o2) {
      return o1.app.name.compareToIgnoreCase(o2.app.name);
    }
  }

  protected static class SortAppsByUid implements Comparator<GroupItem> {
    public int compare(GroupItem o1, GroupItem o2) {
      return o1.app.uid < o2.app.uid ? -1 : (o1.app.uid == o2.app.uid) ? 0 : 1;
    }
  }

  protected void preSortData() {
    Comparator<GroupItem> sortMethod;

    switch(preSortBy) {
      case UID:
        sortMethod = new SortAppsByUid();
        break;

      case NAME:
        sortMethod = new SortAppsByName();
        break;

      case PACKETS:
        sortMethod = new SortAppsByPackets();
        break;

      case BYTES:
        sortMethod = new SortAppsByBytes();
        break;

      case TIMESTAMP:
        sortMethod = new SortAppsByTimestamp();
        break;

      default:
        return;
    }

    synchronized(groupData) {
      Collections.sort(groupData, sortMethod);
    }
  }

  protected void sortData() {
    Comparator<GroupItem> sortMethod;

    switch(sortBy) {
      case UID:
        sortMethod = new SortAppsByUid();
        break;

      case NAME:
        sortMethod = new SortAppsByName();
        break;

      case PACKETS:
        sortMethod = new SortAppsByPackets();
        break;

      case BYTES:
        sortMethod = new SortAppsByBytes();
        break;

      case TIMESTAMP:
        sortMethod = new SortAppsByTimestamp();
        break;

      default:
        return;
    }

    synchronized(groupData) {
      Collections.sort(groupData, sortMethod);
    }
  }

  public void setDoNotRefresh(boolean value) {
    doNotRefresh = value;
  }

  public void refreshAdapter() {
    if(doNotRefresh) {
      return;
    }

    int index = listView.getFirstVisiblePosition();
    View v = listView.getChildAt(0);
    int top = (v == null) ? 0 : v.getTop();

    adapter.notifyDataSetChanged();
    MyLog.d("Refreshed AppFragment adapter");

    listView.setSelectionFromTop(index, top);

    int size = adapter.getGroupCount();
    for(int i = 0; i < size; i++) {
      if(((GroupItem)adapter.getGroup(i)).isExpanded == true) {
        listView.expandGroup(i);
      } else {
        listView.collapseGroup(i);
      }
    }
  }

  protected void getInstalledApps() {
    synchronized(groupDataBuffer) {
      synchronized(groupData) {
        groupData.clear();
        groupDataBuffer.clear();

        synchronized(ApplicationsTracker.installedAppsLock) {
          for(ApplicationsTracker.AppEntry app : ApplicationsTracker.installedApps) {
            if(NetworkLog.state != NetworkLog.State.RUNNING && NetworkLog.initRunner.running == false) {
              MyLog.d("[AppFragment] Initialization aborted");
              return;
            }

            GroupItem item = new GroupItem();
            item.app = app;
            item.lastTimestamp = 0;
            item.childrenData = new HashMap<String, ChildItem>();
            item.filteredChildItems = new HashMap<String, ChildItem>();
            item.packetGraphBuffer = new ArrayList<PacketGraphItem>();
            groupData.add(item);
            groupDataBuffer.add(item);
          }
        }

        Activity activity = getActivity();

        if(activity != null) {
          activity.runOnUiThread(new Runnable() {
            public void run() {
              preSortData();
              setFilter("");
              refreshAdapter();
            }
          });
        }

        // groupDataBuffer must always be sorted by UID for binary search
        Collections.sort(groupDataBuffer, new SortAppsByUid());
      }
    }
  }

  @Override
    public void onSaveInstanceState(Bundle outState) {
      super.onSaveInstanceState(outState);
      setUserVisibleHint(true);
    }

  public void setParent(NetworkLog parent) {
    this.parent = parent;
  }

  @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
      super.setUserVisibleHint(isVisibleToUser);

      if (this.isVisible() && !isVisibleToUser) {
        if(parent != null) {
          parent.invalidateOptionsMenu();
        }
      }
    }

  /** Called when the activity is first created. */
  @Override
    public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      MyLog.d("AppFragment onCreate");

      setRetainInstance(true);

      sortBy = NetworkLog.settings.getSortBy();
      preSortBy = NetworkLog.settings.getPreSortBy();

      groupData = new ArrayList<GroupItem>();
      groupDataBuffer = new ArrayList<GroupItem>();
      cachedSearchItem = new GroupItem();
      cachedSearchItem.app = new ApplicationsTracker.AppEntry();

      adapter = new CustomAdapter();
    }

  @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
        Bundle savedInstanceState) {
      Context context = getActivity().getApplicationContext();

      MyLog.d("[AppFragment] onCreateView");

      if(NetworkLog.settings == null) {
        NetworkLog activity = (NetworkLog) getActivity();

        if(activity != null) {
          activity.loadSettings();
        }
      }

      LinearLayout layout = new LinearLayout(context);
      layout.setOrientation(LinearLayout.VERTICAL);

      TextView tv = new TextView(context);
      tv.setText("Press for connections, long-press for graph");
      layout.addView(tv);

      listView = new ExpandableListView(context);
      listView.setAdapter(adapter);
      listView.setTextFilterEnabled(true);
      listView.setFastScrollEnabled(true);
      listView.setSmoothScrollbarEnabled(false);
      listView.setGroupIndicator(null);
      listView.setChildIndicator(null);
      listView.setDividerHeight(0);
      listView.setChildDivider(getResources().getDrawable(R.color.transparent));
      layout.addView(listView);

      listView.setOnGroupExpandListener(new OnGroupExpandListener() {
        @Override
        public void onGroupExpand(int groupPosition) {
          ((GroupItem)adapter.getGroup(groupPosition)).isExpanded = true;
        }
      });

      listView.setOnGroupCollapseListener(new OnGroupCollapseListener() {
        @Override
        public void onGroupCollapse(int groupPosition) {
          ((GroupItem)adapter.getGroup(groupPosition)).isExpanded = false;
        }
      });

      listView.setOnItemLongClickListener(new OnItemLongClickListener() {
        @Override
        public boolean onItemLongClick(AdapterView parent, View v, 
          int position, long id) 
        {
          /* Don't handle long clicks for child elements (will use context menu instead) */
          if (ExpandableListView.getPackedPositionType(id) != ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
            GroupItem group = (GroupItem) adapter.getGroup(ExpandableListView.getPackedPositionGroup(id));
            showGraph(group.app.uidString);
            return true;
          } else {
            return false;
          }
        }
      });

      listView.setOnChildClickListener(new OnChildClickListener() {
        @Override
        public boolean onChildClick(ExpandableListView parent, View v, 
          int groupPosition, int childPosition, long id)
        {
          GroupItem group = (GroupItem) adapter.getGroup(groupPosition);
          ChildItem child = (ChildItem) adapter.getChild(groupPosition, childPosition);

          getActivity().startActivity(new Intent(getActivity().getApplicationContext(), AppTimelineGraph.class)
            .putExtra("app_uid", group.app.uidString)
            .putExtra("src_addr", child.receivedAddress)
            .putExtra("src_port", child.receivedPort)
            .putExtra("dst_addr", child.sentAddress)
            .putExtra("dst_port", child.sentPort));

          return true;
        }
      });

      registerForContextMenu(listView);

      if(gotInstalledApps == false) {
        getInstalledApps();
        gotInstalledApps = true;
      }

      startUpdater();

      return layout;
    }

  @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
      super.onCreateContextMenu(menu, v, menuInfo);

      ExpandableListView.ExpandableListContextMenuInfo info =
        (ExpandableListView.ExpandableListContextMenuInfo) menuInfo;

      int type = ExpandableListView.getPackedPositionType(info.packedPosition);
      int group = ExpandableListView.getPackedPositionGroup(info.packedPosition);
      int child = ExpandableListView.getPackedPositionChild(info.packedPosition);

      // Only create a context menu for child items
      if (type == ExpandableListView.PACKED_POSITION_TYPE_CHILD) {
        MenuInflater inflater = getActivity().getMenuInflater();
        inflater.inflate(R.layout.app_context_menu, menu);
      }
    }

  @Override
    public boolean onContextItemSelected(MenuItem item) {
      ExpandableListContextMenuInfo info;
      int groupPos;
      int childPos;

      switch(item.getItemId()) {
        case R.id.app_copy_ip:
          info = (ExpandableListContextMenuInfo) item.getMenuInfo();
          groupPos = ExpandableListView.getPackedPositionGroup(info.packedPosition);
          childPos = ExpandableListView.getPackedPositionChild(info.packedPosition);

          ChildItem childItem = (ChildItem) adapter.getChild(groupPos, childPos);
          copyIpAddress(childItem);
          return true;
        case R.id.app_graph:
          info = (ExpandableListContextMenuInfo) item.getMenuInfo();
          groupPos = ExpandableListView.getPackedPositionGroup(info.packedPosition);
          childPos = ExpandableListView.getPackedPositionChild(info.packedPosition);

          GroupItem groupItem = (GroupItem) adapter.getGroup(groupPos);
          showGraph(groupItem.app.uidString);
          return true;
        default:
          return super.onContextItemSelected(item);
      }
    }

  @SuppressWarnings("deprecation")
  void copyIpAddress(ChildItem childItem) {
    String hostString = "";

    if(childItem.sentPackets > 0 && childItem.out != null) {
      String sentAddressString;
      String sentPortString;

      if(NetworkLog.resolveHosts && NetworkLog.resolveCopies) {
        sentAddressString = NetworkLog.resolver.resolveAddress(childItem.sentAddress);

        if(sentAddressString == null) {
          sentAddressString = childItem.sentAddress;
        }

      } else {
        sentAddressString = childItem.sentAddress;
      }

      if(NetworkLog.resolvePorts && NetworkLog.resolveCopies) {
        sentPortString = NetworkLog.resolver.resolveService(String.valueOf(childItem.sentPort));
      } else {
        sentPortString = String.valueOf(childItem.sentPort);
      }

      hostString = sentAddressString + ":" + sentPortString;
    }
    else if(childItem.receivedPackets > 0 && childItem.in != null) {
      String receivedAddressString;
      String receivedPortString;

      if(NetworkLog.resolveHosts && NetworkLog.resolveCopies) {
        receivedAddressString = NetworkLog.resolver.resolveAddress(childItem.receivedAddress);

        if(receivedAddressString == null) {
          receivedAddressString = childItem.receivedAddress;
        }

      } else {
        receivedAddressString = childItem.receivedAddress;
      }

      if(NetworkLog.resolvePorts && NetworkLog.resolveCopies) {
        receivedPortString = NetworkLog.resolver.resolveService(String.valueOf(childItem.receivedPort));
      } else {
        receivedPortString = String.valueOf(childItem.receivedPort);
      }

      hostString = receivedAddressString + ":" + receivedPortString;
    }

    ClipboardManager clipboard = (ClipboardManager) getActivity().getSystemService(Context.CLIPBOARD_SERVICE);

    /* newer API 11 clipboard unsupported on older devices
    ClipData clip = ClipData.newPlainText("NetworkLog IP Address", hostString);
    clipboard.setPrimaryClip(clip);
    */

    /* use older deprecated ClipboardManager to support older devices */
    clipboard.setText(hostString);
  }

  void showGraph(String appuid) {
    getActivity().startActivity(new Intent(getActivity().getApplicationContext(), AppTimelineGraph.class)
        .putExtra("app_uid", appuid));
  }

  Comparator comparator = new Comparator<GroupItem>() {
    public int compare(GroupItem o1, GroupItem o2) {
      return o1.app.uid < o2.app.uid ? -1 : (o1.app.uid == o2.app.uid) ? 0 : 1;
    }
  };

  public int getItemByAppUid(int uid) {
    synchronized(groupDataBuffer) {
      // check to see if we need to search for index
      // (more often than not, the last index is still the active index being requested)
      if(lastGetItemByAppUidIndex < 0 || groupDataBuffer.get(lastGetItemByAppUidIndex).app.uid != uid) {
        cachedSearchItem.app.uid = uid;
        lastGetItemByAppUidIndex = Collections.binarySearch(groupDataBuffer, cachedSearchItem, comparator);
      }

      // binarySearch isn't guaranteed to return the first item of items with the same uid
      // so find the first item
      while(lastGetItemByAppUidIndex > 0) {
        if(groupDataBuffer.get(lastGetItemByAppUidIndex - 1).app.uid == uid) {
          lastGetItemByAppUidIndex--;
        }
        else {
          break;
        }
      }
    }

    return lastGetItemByAppUidIndex;
  }

  public void rebuildLogEntries() {
    synchronized(groupDataBuffer) {
      clear();

      Iterator<LogFragment.ListItem> iterator = NetworkLog.logFragment.listDataUnfiltered.iterator();
      LogEntry entry = new LogEntry();
      while(iterator.hasNext()) {
        LogFragment.ListItem item = iterator.next();

        entry.uid = item.mUid;
        entry.in = item.in;
        entry.out = item.out;
        entry.src = item.srcAddr;
        entry.dst = item.dstAddr;
        entry.len = item.len;
        entry.spt = item.srcPort;
        entry.dpt = item.dstPort;
        entry.timestamp = item.timestamp;

        onNewLogEntry(entry);
      }
      groupDataBufferIsDirty = true;
    }
  }

  private StringBuilder srcSb = new StringBuilder(256);
  private StringBuilder dstSb = new StringBuilder(256);

  public void onNewLogEntry(final LogEntry entry) {
    if(MyLog.enabled) {
      MyLog.d("AppFragment: NewLogEntry: [" + entry.uid + "] in=" + entry.in + " out=" + entry.out + " " + entry.src + ":" + entry.spt + " --> " + entry.dst + ":" + entry.dpt + " [" + entry.len + "]");
    }

    int index = getItemByAppUid(entry.uid);

    if(index < 0) {
      MyLog.d("No app entry");
      return;
    }

    synchronized(groupDataBuffer) {
      srcSb.setLength(0);
      String src = srcSb.append(entry.src).append(":").append(entry.spt).toString();
      dstSb.setLength(0);
      String dst = dstSb.append(entry.dst).append(":").append(entry.dpt).toString();

      // generally this will iterate once, but some apps may be grouped under the same uid
      while(true) {
        GroupItem item = groupDataBuffer.get(index);

        if(item.app.uid != entry.uid) {
          break;
        }

        groupDataBufferIsDirty = true;

        PacketGraphItem graphItem = new PacketGraphItem(entry.timestamp, entry.len);

        item.packetGraphBuffer.add(graphItem);
        item.totalPackets++;
        item.totalBytes += entry.len;
        item.lastTimestamp = entry.timestamp;

        ChildItem childData;

        if(entry.in != null && entry.in.length() != 0) {
          synchronized(item.childrenData) {
            childData = item.childrenData.get(src);

            if(childData == null) {
              childData = new ChildItem();
              childData.packetGraphBuffer = new ArrayList<PacketGraphItem>();
            }

            childData.in = entry.in;

            childData.out = null;
            childData.receivedPackets++;
            childData.receivedBytes += entry.len;
            childData.receivedTimestamp = entry.timestamp;

            if(MyLog.enabled) {
              MyLog.d("Added received packet index=" + index + " in=" + entry.in + " out=" + entry.out + " " + entry.src + ":" + entry.spt + " --> " + entry.dst + ":" + entry.dpt + "; total: " + childData.receivedPackets + "; bytes: " + childData.receivedBytes);
            }

            childData.receivedPort = entry.spt;
            childData.receivedAddress = entry.src;

            childData.sentPort = entry.dpt;
            childData.sentAddress = entry.dst;

            childData.packetGraphBuffer.add(graphItem);

            item.childrenData.put(src, childData);
            item.childrenDataNeedsSort = true;
          }
        }

        if(entry.out != null && entry.out.length() != 0) {
          synchronized(item.childrenData) {
            childData = item.childrenData.get(dst);

            if(childData == null) {
              childData = new ChildItem();
              childData.packetGraphBuffer = new ArrayList<PacketGraphItem>();
            }

            childData.in = null;
            childData.out = entry.out;
            childData.sentPackets++;
            childData.sentBytes += entry.len;
            childData.sentTimestamp = entry.timestamp;

            if(MyLog.enabled) {
              MyLog.d("Added sent packet index=" + index + " in=" + entry.in + " out=" + entry.out + " " + entry.src + ":" + entry.spt + " --> " + entry.dst + ":" + entry.dpt + "; total: " + childData.sentPackets + "; bytes: " + childData.sentBytes);
            }

            childData.sentPort = entry.dpt;
            childData.sentAddress = entry.dst;

            childData.receivedPort = entry.spt;
            childData.receivedAddress = entry.src;

            childData.packetGraphBuffer.add(graphItem);

            item.childrenData.put(dst, childData);
            item.childrenDataNeedsSort = true;
          }
        }

        index++;

        if(index >= groupDataBuffer.size()) {
          break;
        }
      }
    }
  }

  public void startUpdater() {
    updater = new ListViewUpdater();
    new Thread(updater, "AppFragmentUpdater").start();
  }

  public void stopUpdater() {
    if(updater != null) {
      updater.stop();
    }
  }

  // todo: this is largely duplicated in LogFragment -- move to its own file
  private class ListViewUpdater implements Runnable {
    boolean running = false;
    Runnable runner = new Runnable() {
      public void run() {
        synchronized(groupData) {
          MyLog.d("AppFragmentListUpdater enter");

          synchronized(groupDataBuffer) {
            // todo: find a way so that we don't have to go through every entry
            // in groupDataBuffer here (maybe use some sort of reference mapping)
            for(GroupItem item : groupDataBuffer) {
              if(item.childrenDataNeedsSort) {
                if(MyLog.enabled) {
                  MyLog.d("Updating " + item);
                }
                item.childrenDataNeedsSort = false;

                //sortChildrenData(item);
              }
            }
          }

          preSortData();
          sortData();
          setFilter("");
          refreshAdapter();
        }

        MyLog.d("AppFragmentListUpdater exit");
      }
    };

    public void stop() {
      running = false;
    }

    public void run() {
      running = true;
      MyLog.d("Starting AppFragmentUpdater " + this);

      while(running) {
        if(groupDataBufferIsDirty == true) {
          Activity activity = getActivity();
          if(activity != null) {
            activity.runOnUiThread(runner);
          }
          groupDataBufferIsDirty = false;
        }

        try {
          Thread.sleep(1000);
        } catch(Exception e) {
          Log.d("NetworkLog", "AppFragmentListUpdater", e);
        }
      }

      MyLog.d("Stopped AppFragment updater " + this);
    }
  }

  public void setFilter(CharSequence s) {
    MyLog.d("[AppFragment] setFilter(" + s + ")");
    adapter.getFilter().filter(s);
  }

  private class CustomAdapter extends BaseExpandableListAdapter implements Filterable {
    LayoutInflater mInflater = (LayoutInflater) getActivity().getSystemService(Activity.LAYOUT_INFLATER_SERVICE);

    CustomFilter filter;

    private class CustomFilter extends Filter {
      ArrayList<GroupItem> originalItems = new ArrayList<GroupItem>();
      FilterResults results = new FilterResults();
      ArrayList<GroupItem> filteredItems = new ArrayList<GroupItem>();
      ArrayList<GroupItem> localItems = new ArrayList<GroupItem>();

      @Override
        protected FilterResults performFiltering(CharSequence constraint) {
          MyLog.d("[AppFragment] performFiltering");

          synchronized(groupDataBuffer) {
            originalItems.clear();
            originalItems.addAll(groupDataBuffer);
          }

          if(NetworkLog.filterTextInclude.length() == 0 && NetworkLog.filterTextExclude.length() == 0) {
            MyLog.d("[AppFragment] no constraint item count: " + originalItems.size());

            // undo uniqueHosts filtering
            for(GroupItem item : originalItems) {
              if(item.childrenAreFiltered) {
                item.childrenAreFiltered = false;
                item.filteredChildItems.clear();
              }
            }

            results.values = originalItems;
            results.count = originalItems.size();
          } else {
            filteredItems.clear();
            localItems.clear();
            localItems.addAll(originalItems);
            int count = localItems.size();

            MyLog.d("[AppFragment] item count: " + count);

            if(NetworkLog.filterTextIncludeList.size() == 0) {
              MyLog.d("[AppFragment] no include filter, adding all items");

              for(GroupItem item : localItems) {
                filteredItems.add(item);

                synchronized(item.childrenData) {
                  List<String> list = new ArrayList<String>(item.childrenData.keySet());
                  // todo: sort by user preference
                  Collections.sort(list);
                  Iterator<String> itr = list.iterator();

                  item.filteredChildItems.clear();

                  while(itr.hasNext()) {
                    String host = itr.next();
                    ChildItem childData = item.childrenData.get(host);
                    MyLog.d("[AppFragment] adding filtered host " + childData);
                    item.filteredChildItems.put(host, childData);
                    item.childrenAreFiltered = true;
                  }
                }
              }
            } else {
              if(NetworkLog.filterNameInclude
                  || NetworkLog.filterUidInclude
                  || NetworkLog.filterAddressInclude
                  || NetworkLog.filterPortInclude) 
              {
                for(int i = 0; i < count; i++) {
                  GroupItem item = localItems.get(i);
                  // MyLog.d("[AppFragment] testing filtered item " + item + "; includes: [" + NetworkLog.filterTextInclude + "]");

                  boolean item_added = false;
                  boolean matched = false;

                  for(String c : NetworkLog.filterTextIncludeList) {
                    if((NetworkLog.filterNameInclude && item.app.nameLowerCase.contains(c))
                        || (NetworkLog.filterUidInclude && item.app.uidString.equals(c))) {
                      matched = true;
                        }
                  }

                  if(matched) {
                    // test filter against address/port
                    if(NetworkLog.filterAddressInclude || NetworkLog.filterPortInclude) {
                      synchronized(item.childrenData) {
                        List<String> list = new ArrayList<String>(item.childrenData.keySet());
                        // todo: sort by user preference (bytes, timestamp, address, ports)
                        Collections.sort(list);
                        Iterator<String> itr = list.iterator();

                        item.filteredChildItems.clear();

                        while(itr.hasNext()) {
                          String host = itr.next();
                          // MyLog.d("[AppFragment] testing " + host);

                          ChildItem childData = item.childrenData.get(host);

                          matched = false;

                          String sentAddressResolved;
                          String sentPortResolved;
                          String receivedAddressResolved;
                          String receivedPortResolved;

                          if(NetworkLog.resolveHosts) {
                            sentAddressResolved = NetworkLog.resolver.resolveAddress(childData.sentAddress);

                            if(sentAddressResolved == null) {
                              sentAddressResolved = "";
                            }

                            receivedAddressResolved = NetworkLog.resolver.resolveAddress(childData.receivedAddress);

                            if(receivedAddressResolved == null) {
                              receivedAddressResolved = "";
                            }
                          } else {
                            sentAddressResolved = "";
                            receivedAddressResolved = "";
                          }

                          if(NetworkLog.resolvePorts) {
                            sentPortResolved = NetworkLog.resolver.resolveService(String.valueOf(childData.sentPort));
                            receivedPortResolved = NetworkLog.resolver.resolveService(String.valueOf(childData.receivedPort));
                          } else {
                            sentPortResolved = "";
                            receivedPortResolved = "";
                          }

                          for(String c : NetworkLog.filterTextIncludeList) {
                            if((NetworkLog.filterAddressInclude && ((childData.sentPackets > 0 && (childData.sentAddress.contains(c) || StringPool.getLowerCase(sentAddressResolved).contains(c)))
                                    || (childData.receivedPackets > 0 && (childData.receivedAddress.contains(c) || StringPool.getLowerCase(receivedAddressResolved).contains(c)))))
                                || (NetworkLog.filterPortInclude && ((childData.sentPackets > 0 && (String.valueOf(childData.sentPort).equals(c) || StringPool.getLowerCase(sentPortResolved).equals(c)))
                                    || (childData.receivedPackets > 0 && (String.valueOf(childData.receivedPort).equals(c) || StringPool.getLowerCase(receivedPortResolved).equals(c)))))) {
                              matched = true;
                                    }
                          }

                          if(matched) {
                            if(!item_added) {
                              // MyLog.d("[AppFragment] adding filtered item " + item);
                              filteredItems.add(item);
                              item_added = true;
                            }

                            // MyLog.d("[AppFragment] adding filtered host " + childData);
                            item.filteredChildItems.put(host, childData);
                            item.childrenAreFiltered = true;
                          }
                        }
                      }
                    } else {
                      // no filtering for host/port, matches everything
                      // MyLog.d("[AppFragment] no filter for host/port; adding filtered item " + item);
                      filteredItems.add(item);

                      synchronized(item.childrenData) {
                        List<String> list = new ArrayList<String>(item.childrenData.keySet());
                        // todo: sort by user preference
                        Collections.sort(list);
                        Iterator<String> itr = list.iterator();

                        item.filteredChildItems.clear();

                        while(itr.hasNext()) {
                          String host = itr.next();
                          ChildItem childData = item.childrenData.get(host);
                          // MyLog.d("[AppFragment] adding filtered host " + childData);
                          item.filteredChildItems.put(host, childData);
                          item.childrenAreFiltered = true;
                        }
                      }
                    }
                  }
                }
              }
            }

            if(NetworkLog.filterTextExcludeList.size() > 0) {
              count = filteredItems.size();

              for(int i = count - 1; i >= 0; i--) {
                GroupItem item = filteredItems.get(i);
                // MyLog.d("[AppFragment] testing filtered item: " + i + " " + item + "; excludes: [" + NetworkLog.filterTextExclude + "]");

                boolean matched = false;

                for(String c : NetworkLog.filterTextExcludeList) {
                  if((NetworkLog.filterNameExclude && item.app.nameLowerCase.contains(c))
                      || NetworkLog.filterUidExclude && item.app.uidString.equals(c)) 
                  {
                    matched = true;
                  }
                }

                if(matched) {
                  // MyLog.d("[AppFragment] removing filtered item: " + item);
                  filteredItems.remove(i);
                  continue;
                }

                List<String> list = new ArrayList<String>(item.filteredChildItems.keySet());
                Iterator<String> itr = list.iterator();

                while(itr.hasNext()) {
                  String host = itr.next();
                  ChildItem childData = item.filteredChildItems.get(host);

                  matched = false;

                  String sentAddressResolved;
                  String sentPortResolved;
                  String receivedAddressResolved;
                  String receivedPortResolved;

                  if(NetworkLog.resolveHosts) {
                    sentAddressResolved = NetworkLog.resolver.resolveAddress(childData.sentAddress);

                    if(sentAddressResolved == null) {
                      sentAddressResolved = "";
                    }

                    receivedAddressResolved = NetworkLog.resolver.resolveAddress(childData.receivedAddress);

                    if(receivedAddressResolved == null) {
                      receivedAddressResolved = "";
                    }
                  } else {
                    sentAddressResolved = "";
                    receivedAddressResolved = "";
                  }

                  if(NetworkLog.resolvePorts) {
                    sentPortResolved = NetworkLog.resolver.resolveService(String.valueOf(childData.sentPort));
                    receivedPortResolved = NetworkLog.resolver.resolveService(String.valueOf(childData.receivedPort));
                  } else {
                    sentPortResolved = "";
                    receivedPortResolved = "";
                  }

                  for(String c : NetworkLog.filterTextExcludeList) {
                    if((NetworkLog.filterAddressExclude && ((childData.sentPackets > 0 && (childData.sentAddress.contains(c) || StringPool.getLowerCase(sentAddressResolved).contains(c)))
                            || (childData.receivedPackets > 0 && (childData.receivedAddress.contains(c) || StringPool.getLowerCase(receivedAddressResolved).contains(c)))))
                        || (NetworkLog.filterPortExclude && ((childData.sentPackets > 0 && (String.valueOf(childData.sentPort).equals(c) || StringPool.getLowerCase(sentPortResolved).equals(c)))
                            || (childData.receivedPackets > 0 && (String.valueOf(childData.receivedPort).equals(c) || StringPool.getLowerCase(receivedPortResolved).equals(c)))))) {
                      matched = true;
                            }
                  }

                  if(matched) {
                    // MyLog.d("[AppFragment] removing filtered host " + childData);
                    item.filteredChildItems.remove(host);
                  }
                }

                if(item.filteredChildItems.size() == 0 && matched) {
                  // MyLog.d("[AppFragment] removed all hosts, removing item from filter results");
                  filteredItems.remove(i);
                }
              }
            }

            results.values = filteredItems;
            results.count = filteredItems.size();
          }

          MyLog.d("[AppFragment] filter returning " + results.count + " results");
          return results;
        }

      @SuppressWarnings("unchecked")
        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
          MyLog.d("[AppFragment] Publishing filter results");
          
          final ArrayList<GroupItem> localItems = (ArrayList<GroupItem>) results.values;

          if(localItems == null) {
            MyLog.d("[AppFragment] local items null, wtf");
            return;
          }

          synchronized(groupData) {
            groupData.clear();
            groupData.addAll(localItems);

            preSortData();
            sortData();

            refreshAdapter();
          }
        }
    }

    @Override
      public CustomFilter getFilter() {
        if(filter == null) {
          filter = new CustomFilter();
        }

        return filter;
      }

    @Override
      public Object getChild(int groupPosition, int childPosition) {
        GroupItem groupItem = groupData.get(groupPosition);

        if(groupItem.childrenAreFiltered == false) {
          Set<String> set = (Set<String>) groupItem.childrenData.keySet();
          return groupItem.childrenData.get(set.toArray()[childPosition]);
        } else {
          Set<String> set = (Set<String>) groupItem.filteredChildItems.keySet();
          return groupItem.filteredChildItems.get(set.toArray()[childPosition]);
        }
      }

    @Override
      public long getChildId(int groupPosition, int childPosition) {
        return childPosition;
      }

    @Override
      public int getChildrenCount(int groupPosition) {
        GroupItem groupItem = groupData.get(groupPosition);

        if(groupItem.childrenAreFiltered == false) {
          return groupItem.childrenData.size();
        } else {
          return groupItem.filteredChildItems.size();
        }
      }

    @Override
      public Object getGroup(int groupPosition) {
        return groupData.get(groupPosition);
      }

    @Override
      public int getGroupCount() {
        return groupData.size();
      }

    @Override
      public long getGroupId(int groupPosition) {
        return groupPosition;
      }

    @Override
      public boolean hasStableIds() {
        return true;
      }

    @Override
      public boolean isChildSelectable(int arg0, int arg1) {
        return true;
      }

    @Override
      public View getGroupView(int groupPosition, boolean isExpanded,
          View convertView, ViewGroup parent)
      {
        GroupViewHolder holder = null;

        ImageView icon;
        TextView name;
        TextView packets;
        TextView bytes;
        TextView timestamp;
        TextView hosts;

        GroupItem item;

        synchronized(groupData) {
          item = groupData.get(groupPosition);
        }

        if(convertView == null) {
          convertView = mInflater.inflate(R.layout.appitem, null);
          holder = new GroupViewHolder(convertView);
          convertView.setTag(holder);
        } else {
          holder = (GroupViewHolder) convertView.getTag();
        }

        if(groupPosition == 0) {
          holder.getDivider().setVisibility(View.GONE);
        } else {
          holder.getDivider().setVisibility(View.VISIBLE);
        }

        icon = holder.getIcon();

        if(item.app.icon == null) {
          item.app.icon = ApplicationsTracker.loadIcon(getActivity().getApplicationContext(), item.app.packageName);
        }

        icon.setImageDrawable(item.app.icon);

        name = holder.getName();

        name.setText("(" + item.app.uid + ")" + " " + item.app.name);

        packets = holder.getPackets();
        packets.setText("Packets: " + item.totalPackets);

        bytes = holder.getBytes();
        bytes.setText("Bytes: " + item.totalBytes);

        timestamp = holder.getTimestamp();

        if(item.lastTimestamp != 0) {
          timestamp.setText("(" + Timestamp.getTimestamp(item.lastTimestamp) + ")");
          timestamp.setVisibility(View.VISIBLE);
        } else {
          timestamp.setVisibility(View.GONE);
        }

        return convertView;
      }

    @Override
      public View getChildView(int groupPosition, int childPosition,
          boolean isLastChild, View convertView, ViewGroup parent) 
      {
        ChildViewHolder holder = null;

        TextView host;

        TextView sentPackets;
        TextView sentBytes;
        TextView sentTimestamp;

        TextView receivedPackets;
        TextView receivedBytes;
        TextView receivedTimestamp;

        ChildItem item;

        synchronized(groupData) {
          item = (ChildItem) getChild(groupPosition, childPosition);
        }

        if(item == null) {
          MyLog.d("child (" + groupPosition + "," + childPosition + ") not found");
          return null;
        }

        if(convertView == null) {
          convertView = mInflater.inflate(R.layout.hostitem, null);
          holder = new ChildViewHolder(convertView);
          convertView.setTag(holder);
        } else {
          holder = (ChildViewHolder) convertView.getTag();
        }

        host = holder.getHost();

        String hostString = null;

        if(item.sentPackets > 0 && item.out != null) {
          String sentAddressString;
          String sentPortString;

          if(NetworkLog.resolveHosts) {
            sentAddressString = NetworkLog.resolver.resolveAddress(item.sentAddress);

            if(sentAddressString == null) {
              sentAddressString = item.sentAddress;
            }

          } else {
            sentAddressString = item.sentAddress;
          }

          if(NetworkLog.resolvePorts) {
            sentPortString = NetworkLog.resolver.resolveService(String.valueOf(item.sentPort));
          } else {
            sentPortString = String.valueOf(item.sentPort);
          }

          hostString = sentAddressString + ":" + sentPortString;
        }
        else if(item.receivedPackets > 0 && item.in != null) {
          String receivedAddressString;
          String receivedPortString;

          if(NetworkLog.resolveHosts) {
            receivedAddressString = NetworkLog.resolver.resolveAddress(item.receivedAddress);

            if(receivedAddressString == null) {
              receivedAddressString = item.receivedAddress;
            }

          } else {
            receivedAddressString = item.receivedAddress;
          }

          if(NetworkLog.resolvePorts) {
            receivedPortString = NetworkLog.resolver.resolveService(String.valueOf(item.receivedPort));
          } else {
            receivedPortString = String.valueOf(item.receivedPort);
          }

          hostString = receivedAddressString + ":" + receivedPortString;
        }

        host.setText(Html.fromHtml("<u>" + hostString + "</u>"));

        sentPackets = holder.getSentPackets();
        sentBytes = holder.getSentBytes();
        sentTimestamp = holder.getSentTimestamp();

        if(item.sentPackets > 0) {
          sentPackets.setText(String.valueOf(item.sentPackets));
          sentBytes.setText(String.valueOf(item.sentBytes));

          String timestampString = Timestamp.getTimestamp(item.sentTimestamp);
          sentTimestamp.setText("(" + timestampString.substring(timestampString.indexOf(' ') + 1, timestampString.length()) + ")");

          sentPackets.setVisibility(View.VISIBLE);
          sentBytes.setVisibility(View.VISIBLE);
          sentTimestamp.setVisibility(View.VISIBLE);
          holder.getSentLabel().setVisibility(View.VISIBLE);
          holder.getSentPacketsLabel().setVisibility(View.VISIBLE);
          holder.getSentBytesLabel().setVisibility(View.VISIBLE);
        } else {
          sentPackets.setVisibility(View.GONE);
          sentBytes.setVisibility(View.GONE);
          sentTimestamp.setVisibility(View.GONE);
          holder.getSentLabel().setVisibility(View.GONE);
          holder.getSentPacketsLabel().setVisibility(View.GONE);
          holder.getSentBytesLabel().setVisibility(View.GONE);
        }

        receivedPackets = holder.getReceivedPackets();
        receivedBytes = holder.getReceivedBytes();
        receivedTimestamp = holder.getReceivedTimestamp();

        if(item.receivedPackets > 0) {
          receivedPackets.setText(String.valueOf(item.receivedPackets));
          receivedBytes.setText(String.valueOf(item.receivedBytes));

          String timestampString = Timestamp.getTimestamp(item.receivedTimestamp);
          receivedTimestamp.setText("(" + timestampString.substring(timestampString.indexOf(' ') + 1, timestampString.length()) + ")");
          receivedPackets.setVisibility(View.VISIBLE);
          receivedBytes.setVisibility(View.VISIBLE);
          receivedTimestamp.setVisibility(View.VISIBLE);
          holder.getReceivedLabel().setVisibility(View.VISIBLE);
          holder.getReceivedPacketsLabel().setVisibility(View.VISIBLE);
          holder.getReceivedBytesLabel().setVisibility(View.VISIBLE);
        } else {
          receivedPackets.setVisibility(View.GONE);
          receivedBytes.setVisibility(View.GONE);
          receivedTimestamp.setVisibility(View.GONE);
          holder.getReceivedLabel().setVisibility(View.GONE);
          holder.getReceivedPacketsLabel().setVisibility(View.GONE);
          holder.getReceivedBytesLabel().setVisibility(View.GONE);
        }

        return convertView;
      }
  }

  private class GroupViewHolder {
    private View mView;
    private ImageView mDivider = null;
    private ImageView mIcon = null;
    private TextView mName = null;
    private TextView mPackets = null;
    private TextView mBytes = null;
    private TextView mTimestamp = null;
    private TextView mUniqueHosts = null;

    public GroupViewHolder(View view) {
      mView = view;
    }

    public ImageView getDivider() {
      if(mDivider == null) {
        mDivider = (ImageView) mView.findViewById(R.id.appDivider);
      }

      return mDivider;
    }

    public ImageView getIcon() {
      if(mIcon == null) {
        mIcon = (ImageView) mView.findViewById(R.id.appIconx);
      }

      return mIcon;
    }

    public TextView getName() {
      if(mName == null) {
        mName = (TextView) mView.findViewById(R.id.appName);
      }

      return mName;
    }

    public TextView getPackets() {
      if(mPackets == null) {
        mPackets = (TextView) mView.findViewById(R.id.appPackets);
      }

      return mPackets;
    }

    public TextView getBytes() {
      if(mBytes == null) {
        mBytes = (TextView) mView.findViewById(R.id.appBytes);
      }

      return mBytes;
    }

    public TextView getTimestamp() {
      if(mTimestamp == null) {
        mTimestamp = (TextView) mView.findViewById(R.id.appLastTimestamp);
      }

      return mTimestamp;
    }
  }

  private class ChildViewHolder {
    private View mView;
    private TextView mHost = null;

    private TextView mSentLabel = null;
    private TextView mSentPackets = null;
    private TextView mSentPacketsLabel = null;
    private TextView mSentBytes = null;
    private TextView mSentBytesLabel = null;
    private TextView mSentTimestamp = null;

    private TextView mReceivedLabel = null;
    private TextView mReceivedPackets = null;
    private TextView mReceivedPacketsLabel = null;
    private TextView mReceivedBytes = null;
    private TextView mReceivedBytesLabel = null;
    private TextView mReceivedTimestamp = null;

    public ChildViewHolder(View view) {
      mView = view;
    }

    public TextView getHost() {
      if(mHost == null) {
        mHost = (TextView) mView.findViewById(R.id.hostName);
      }

      return mHost;
    }
    
    public TextView getSentLabel() {
      if(mSentLabel == null) {
        mSentLabel = (TextView) mView.findViewById(R.id.sentLabel);
      }

      return mSentLabel;
    }

    public TextView getSentPacketsLabel() {
      if(mSentPacketsLabel == null) {
        mSentPacketsLabel = (TextView) mView.findViewById(R.id.sentPacketsLabel);
      }

      return mSentPacketsLabel;
    }

    public TextView getSentBytesLabel() {
      if(mSentBytesLabel == null) {
        mSentBytesLabel = (TextView) mView.findViewById(R.id.sentBytesLabel);
      }

      return mSentBytesLabel;
    }

    public TextView getSentPackets() {
      if(mSentPackets == null) {
        mSentPackets = (TextView) mView.findViewById(R.id.sentPackets);
      }

      return mSentPackets;
    }
    
    public TextView getSentBytes() {
      if(mSentBytes == null) {
        mSentBytes = (TextView) mView.findViewById(R.id.sentBytes);
      }

      return mSentBytes;
    }
    
    public TextView getSentTimestamp() {
      if(mSentTimestamp == null) {
        mSentTimestamp = (TextView) mView.findViewById(R.id.sentTimestamp);
      }

      return mSentTimestamp;
    }

    public TextView getReceivedLabel() {
      if(mReceivedLabel == null) {
        mReceivedLabel = (TextView) mView.findViewById(R.id.receivedLabel);
      }

      return mReceivedLabel;
    }

    public TextView getReceivedPacketsLabel() {
      if(mReceivedPacketsLabel == null) {
        mReceivedPacketsLabel = (TextView) mView.findViewById(R.id.receivedPacketsLabel);
      }

      return mReceivedPacketsLabel;
    }

    public TextView getReceivedBytesLabel() {
      if(mReceivedBytesLabel == null) {
        mReceivedBytesLabel = (TextView) mView.findViewById(R.id.receivedBytesLabel);
      }

      return mReceivedBytesLabel;
    }

    public TextView getReceivedPackets() {
      if(mReceivedPackets == null) {
        mReceivedPackets = (TextView) mView.findViewById(R.id.receivedPackets);
      }

      return mReceivedPackets;
    }
    
    public TextView getReceivedBytes() {
      if(mReceivedBytes == null) {
        mReceivedBytes = (TextView) mView.findViewById(R.id.receivedBytes);
      }

      return mReceivedBytes;
    }
    
    public TextView getReceivedTimestamp() {
      if(mReceivedTimestamp == null) {
        mReceivedTimestamp = (TextView) mView.findViewById(R.id.receivedTimestamp);
      }

      return mReceivedTimestamp;
    }
  }
}
