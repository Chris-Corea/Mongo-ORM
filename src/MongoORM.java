import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bson.types.ObjectId;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.Mongo;

public class MongoORM {
	/**
	 * use map<Object,ObjectId> to track whether we have pickled the object to
	 * avoid cycles references to objects with @MongoCollection annot always
	 * convert to ObjectId
	 */
	protected Map<Object, ObjectId> pickled = new HashMap<Object, ObjectId>();
	/** track objects as we load from mongo; don't reload, reuse if we can */
	protected Map<ObjectId, Object> depickled = new HashMap<ObjectId, Object>();

	protected DB db;
	
	private Object object[];

	public MongoORM(DB db) {
		this.db = db;
	}

	public <T> List<T> loadAll(Class<T> clazz) throws Exception {

		MongoCollection mc = clazz.getAnnotation(MongoCollection.class);
		if (mc == null) {
			System.out.println("In <loadAll> :: no MongoCollection.class");
			return null; 
		}
		Class<?> superclass = clazz.getSuperclass();
		boolean hasSuperclass = false;
		
		if (clazz.getSuperclass() != null &&
				superclass.getAnnotation(MongoCollection.class) != null) {
			superclass = clazz.getSuperclass();
			hasSuperclass = true;
		}
		
		DBCollection collection = db.getCollection(clazz.getSimpleName());
		DBCursor cursor = collection.find();
		List<T> list = new ArrayList<T>();
		
		Field fields[] = clazz.getDeclaredFields();
		if (fields == null) {
			System.out.println("In <loadAll> :: fields[] = null");
			return null;
		}
		
		while (cursor.hasNext()) {
			T object = (T) clazz.newInstance();
			BasicDBObject dbObj = (BasicDBObject) cursor.next();
			for (Field f : fields) {
				f.setAccessible(true);
				Type type = f.getGenericType();
				if (!dbObj.containsField(f.getName()))
					continue;
				if (type instanceof ParameterizedType) {  
		            ParameterizedType pt = (ParameterizedType) type;
		            for (Type t : pt.getActualTypeArguments()) {  
		            	getObject(dbObj.get(f.getName()), t.toString());
		            }
				}
				if (f.getType().isAnnotationPresent(MongoCollection.class)) { //manager class is seen as Employee
					f.set(object, depickled.get(dbObj.get(f.getName())));
				}
				else if (dbObj.containsKey((Object) f.getName())) {
					if (dbObj.get(f.getName()) instanceof Collection) {
						Object objArray[] = ((Collection) dbObj.get(f.getName())).toArray();
						List<Object> objectList = new ArrayList<Object>();
						for (int i = 0; i < objArray.length - 1; i++) {
							if (depickled.containsKey(objArray[i])) {
								objectList.add(depickled.get(objArray[i]));
							}
						}
						f.set(object, objectList);
						continue;
					}
					f.set(object, dbObj.get(f.getName()));
				}
			}
			if (hasSuperclass) {
				Field[] superFields = superclass.getDeclaredFields();
				for (Field f : superFields) {
					f.setAccessible(true);
					Type type = f.getGenericType();
					if (!dbObj.containsField(f.getName())) { continue; }
					if (type instanceof ParameterizedType) {
						ParameterizedType pt = (ParameterizedType) type;
						for (Type t : pt.getActualTypeArguments()) {
							getObject(dbObj.get(f.getName()), t.toString());
						}
					}
					if (f.getType().isAnnotationPresent(MongoCollection.class)) {
						f.set(object, depickled.get(dbObj.get(f.getName())));
					}
					else if (dbObj.containsKey((Object) f.getName())) {
						if (dbObj.get(f.getName()) instanceof Collection) {
							Object objArray[] = ((Collection) dbObj.get(f.getName())).toArray();
							List<Object> objectList = new ArrayList<Object>();
							for (int i = 0; i < objArray.length - 1; i++) {
								if (depickled.containsKey(objArray[i])) {
									objectList.add(depickled.get(objArray[i]));
								}
							}
							f.set(object, objectList);
							continue;
						}
						f.set(object, dbObj.get(f.getName()));
					}
				}
			}
			list.add(object);
			depickled.put(dbObj.getObjectId("_id"), object);
		}
		
		return list;
	}
	
	private void getObject(Object object, String className) throws Exception {
		List<Object> list = new ArrayList<Object>();
		if (object instanceof Collection<?>) {
			Object objArray[] = ((Collection) object).toArray();
			
			Class<?> c = Class.forName(objArray[objArray.length-1].toString());
			loadAll(c);
		}
	}

	public void save(Object o) throws Exception {	
		Class<?> c = o.getClass();
		boolean hasSuperclass = false;
		String fieldName = null;
		Class<?> superclass = c.getSuperclass();
		
		// get annotations and superclass
		MongoCollection mc = c.getAnnotation(MongoCollection.class);
		if (mc == null) { // not annotated with MongoCollection
			System.out.println("Class " + c.getSimpleName()
					+ " is not annotated with MongoCollection");
			return;
		}
		if (c.getSuperclass() != null && 
				superclass.getAnnotation(MongoCollection.class) != null) {
			superclass = c.getSuperclass();
			hasSuperclass = true;
		}
		// get fields
		Field fields[] = c.getDeclaredFields();
		if (fields == null) {
			System.out.println("Class " + c.getSimpleName()
					+ " has no fields!\nReturning");
			return;
		}
		DBCollection collection;
		if (!mc.value().equals("")) {
			collection = db.getCollection(mc.value());
		}
		else {
			collection = db.getCollection(c.getSimpleName());
		}
		
		BasicDBObject dbObject = new BasicDBObject();
		for (Field f : fields) {
			MongoField mf = f.getAnnotation(MongoField.class);
 			if (mf != null) {
				f.setAccessible(true);
				if (f.get(o) == null) { continue; }
				if (mf.value().equals("")) {
					fieldName = f.getName();
				}
				else {
					fieldName = mf.value();
				}
				//System.out.println(f.getName());
				Type type = f.getGenericType();
		        if (type instanceof ParameterizedType) {  
		            dbObject.put(fieldName, saveObject(f.get(o)));
		            continue;
		        }
				
				if (pickled.containsKey(f.get(o))) {
					ObjectId referencedObject = pickled.get(f.get(o));
					dbObject.put(fieldName, referencedObject);
					continue;
				}
				dbObject.put(fieldName, f.get(o));
			}
		}
		if (hasSuperclass) {
			dbObject.put("super class", superclass.getSimpleName());
			Field[] superFields = superclass.getDeclaredFields();
			for (Field f : superFields) {
				MongoField mf = f.getAnnotation(MongoField.class);
				if (mf != null) {
					f.setAccessible(true);
					if (f.get(o) == null) { continue; }
					fieldName = f.getName();
					Type type = f.getGenericType();
					if (type instanceof ParameterizedType) {
						dbObject.put(fieldName, saveObject(f.get(o)));
						continue;
					}
					if (pickled.containsKey(f.get(o))) {
						ObjectId referencedObject = pickled.get(f.get(o));
						dbObject.put(fieldName, referencedObject);
						continue;
					}
					dbObject.put(fieldName, f.get(o));
				}
			}
		}
		dbObject.put("type", c.getSimpleName());
		collection.insert(dbObject);
		pickled.put(o, dbObject.getObjectId("_id"));
	}

	private List<Object> saveObject(Object object) {
		List<Object> list = null;
		if(object instanceof Collection<?>) {
			list = new ArrayList<Object>();
			String classHolder = null;
			for (Object o : ((Collection) object).toArray()) {
				if (o.getClass().isAnnotationPresent(MongoCollection.class)) {
					try {
						save(o);
						list.add(pickled.get(o));
					} catch (Exception e) {
						e.printStackTrace();
					}
				}
				classHolder = o.getClass().getSimpleName();
			}
			list.add(classHolder);
		}
		return list;
	}
	
	/**
	 *  TESTING PURPOSES ONLY
	 */
	public void drop() {
		db.dropDatabase();
	}
}
