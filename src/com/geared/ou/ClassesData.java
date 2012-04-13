/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.geared.ou;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.geared.ou.D2LSourceGetter.SGError;

/**
 *
 * @author David
 */
public class ClassesData {
    private Boolean force;
    private String homeSource;
    ArrayList<Course> courses;
    private Date lastUpdate;
    private OUApplication app;
    private static final long UPDATE_INTERVAL = 2678400000L;

    public class Course {

        private String name;
        private String link;
        private String namePrefix;
        private ContentData content;
        private GradesData grades;
        private int id;
        private int ouId;

        public Course(String name, String link, int id) {
            this.name = name;
            this.link = link;
            this.id = id;
            content = new ContentData(app, this);
            grades = new GradesData(app, this);
        }

        public String getName() {
            return name;
        }

        public String getPrefix() {
            return namePrefix;
        }

        public String getLink() {
            return link;
        }

        public int getId() {
            return id;
        }
        
        public int getOuId()
        {
            return ouId;
        }
        
        public void setOuId(int id) {
            ouId = id;
        }
        
        public void setPrefix(String p)
        {
            namePrefix = p;
        }
        
        public String getURL()
        {
            return link;
        }
        
        public ContentData getContent() {
            return content;
        }
        
        public GradesData getGrades() {
            return grades;
        }

        protected void splitPrefixFromName() {
            /* MAKE THIS SMARTER. */
            namePrefix = name.substring(0, 4);
            name = name.substring(16, name.length());
            name = name.replace("&amp;", "&");
        }
        
        protected void extractOuId() {
            String tmp = link.split("=")[1];
            ouId = Integer.parseInt(tmp);
            // ERROR CHECK THIS?
        }
    }

    public ClassesData(OUApplication c) {
        courses = new ArrayList<Course>();
        lastUpdate = new Date();
        lastUpdate.setTime(lastUpdate.getTime() - UPDATE_INTERVAL - 1);
        force = false;
        app = c;
    }

    public SGError update(D2LSourceGetter sg) {
        // Make sure that sourceGetter has current credientals
        app.updateSGCredentials();
        
        // If this update was force, unset force
        if (force) {
            force = false;
        }
        
        SGError result = SGError.NO_ERROR;
        // SourceGetter.login() will return the source of the homepage
        result = sg.login();
        // If there were no erros in fetching the source, then proceed.
        if (result == SGError.NO_ERROR) {
            Log.d("OU", "sg.login() returned no error");
            homeSource = sg.getPulledSource();
            //If no courses were found in the pulled source, error.
            if (!pullCourseList()) {
                return SGError.NO_DATA;
            }
        }
        return result;
    }

    protected Boolean pullCourseList() {
        courses.clear();
        Document doc = Jsoup.parse(homeSource);
        homeSource = null;
        Elements es = doc.getElementsContainingOwnText("Spring 2012"); //MAKE THIS DYNAMICCC!!!!!
        Element mainDiv;
        Element subDivOfInterest;
        for (Element t : es) {
            if (t.tag().getName().equals("h3")) {
                mainDiv = t.parent().parent().parent();
                subDivOfInterest = mainDiv.getElementsByClass("dco_c").first();
                Elements aElementsForEachClass = subDivOfInterest.getElementsByAttributeValueMatching("href", "/d2l/lp/ouHome/home.*");
                Iterator<Element> i = aElementsForEachClass.listIterator();
                int counter = 0;
                while (i.hasNext()) {
                    Element et = i.next();
                    String link;
                    String name;

                    link = et.attr("href");
                    name = et.html();

                    Course c = new Course(name, link, counter);
                    courses.add(c);
                    counter++;
                }
            }
	}
        cleanCourseNames();
        if (courses.isEmpty()) {
            return false;
        }
        lastUpdate = new Date();
        if (!addCoursesToDb())
            return false;
        
        return true;
    }

    private void cleanCourseNames() {
        Course c;
        for (Iterator<Course> j = courses.iterator(); j.hasNext();) {
            c = j.next();
            c.splitPrefixFromName();
            c.extractOuId();
        }
    }

    public Boolean needUpdate() {
        if (courses.isEmpty()) {
            if(!isDatabaseStale()) {
                populateCoursesFromDB();
            }
        }
        if ((new Date().getTime() - lastUpdate.getTime() < UPDATE_INTERVAL) && !force) {
            return false;
        }
        return true;
    }

    public String getHomeSource() {
        return homeSource;
    }

    public Course getCourse(int id) {
        if (!courses.isEmpty()) {
            if (id < courses.size()) {
                return courses.get(id);
            }
            return courses.get(0);
        }
        return new Course("bad", "bad", -1);
    }

    public Date getLastSourceUpdate() {
        return lastUpdate;
    }

    public ArrayList<Course> getCourseList() {
        return courses;
    }
    
    private Boolean addCoursesToDb()
    {
        SQLiteDatabase db = app.getDb();
        // Delete any classes already in database for the current user
        //db.delete(DbHelper.T_CLASSES, "where user='?'", new String[] {app.getUser()});
        db.rawQuery("delete from classes where user='"+app.getUser()+"'", null);
        
        // Insert each of the pulled courses
        ContentValues values = new ContentValues();
        Course c;
        for (Iterator<Course> j = courses.iterator(); j.hasNext();) {
            c = j.next();
            values.clear();
            values.put(DbHelper.C_ID, c.getId());
            values.put(DbHelper.C_USER, app.getPrefs().getString("username", ""));
            values.put(DbHelper.C_NAME, c.getName());
            values.put(DbHelper.C_COLLEGE_ABVR, c.getPrefix());
            values.put(DbHelper.C_LAST_UPDATE, (int)((new Date().getTime())/1000));
            values.put(DbHelper.C_OUID, c.getOuId());
            values.put(DbHelper.C_URL, c.getLink());
            db.insert(DbHelper.T_CLASSES, null, values);
        }
        
        // Close database
        db.close();
        
        return true;
    }

    public void populateCoursesFromDB() {
        courses.clear();
        SQLiteDatabase db = app.getDb();
        Cursor result = db.rawQuery("select * from classes where user='"+app.getUser()+"'", null);
        int counter = 0;
        while(result.moveToNext())
        {
            if(counter == 0)        
                lastUpdate.setTime(((long)(result.getInt(result.getColumnIndex(DbHelper.C_LAST_UPDATE))))*1000);
            String link = result.getString(result.getColumnIndex(DbHelper.C_URL));
            Course c = new Course(result.getString(result.getColumnIndex(DbHelper.C_NAME)), link, counter);
            c.setPrefix(result.getString(result.getColumnIndex(DbHelper.C_COLLEGE_ABVR)));
            c.setOuId(result.getInt(result.getColumnIndex(DbHelper.C_OUID)));
            courses.add(c);
            counter++;
        }
        db.close();
    }

    public void forceNextUpdate() {
        force = true;
    }

    private Boolean isDatabaseStale() {
        SQLiteDatabase db = app.getDb();
        Cursor result = db.rawQuery("select last_update from classes where user='"+app.getUser()+"'", null);
        result.moveToFirst();
        // If there are no classes in the database for 
        if (result.getCount() < 1) {
            db.close();
            return true;
        }
        if ((new Date().getTime() - ((long)(result.getInt(0)))*1000) > UPDATE_INTERVAL) {
            db.close();
            return true;
        }
        db.close();
        return false;
    }
}
