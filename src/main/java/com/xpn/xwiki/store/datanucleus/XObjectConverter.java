/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package com.xpn.xwiki.store.datanucleus;

import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.lang.reflect.Field;

import com.xpn.xwiki.objects.classes.BaseClass;
import com.xpn.xwiki.objects.classes.PropertyClass;
import com.xpn.xwiki.objects.BaseProperty;
import com.xpn.xwiki.objects.BaseStringProperty;
import com.xpn.xwiki.objects.LargeStringProperty;
import com.xpn.xwiki.objects.StringProperty;
import com.xpn.xwiki.objects.StringListProperty;
import com.xpn.xwiki.objects.DBStringListProperty;
import com.xpn.xwiki.objects.IntegerProperty;
import com.xpn.xwiki.objects.FloatProperty;
import com.xpn.xwiki.objects.DoubleProperty;
import com.xpn.xwiki.objects.LongProperty;
import com.xpn.xwiki.objects.NumberProperty;
import com.xpn.xwiki.objects.ListProperty;
import com.xpn.xwiki.objects.DateProperty;
import com.xpn.xwiki.objects.BaseObject;
import groovy.lang.GroovyClassLoader;
import javax.jdo.JDOEnhancer;
import javax.jdo.JDOHelper;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.tools.GroovyClass;
import org.codehaus.groovy.control.CompilationUnit;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.store.datanucleus.internal.JavaIdentifierEscaper;

/**
 */
public class XObjectConverter
{
    public Object fromXObject(final BaseObject xwikiObject)
    {
        return convertFromXObject(xwikiObject);
    }

    public BaseObject toXObject(final Object persistable, final BaseClass xwikiClass)
    {
        return convertToXObject(persistable, xwikiClass);
    }


    /* ------------------------- Read an XObject into a persistable object. ------------------------- */

    private static Object convertFromXObject(final BaseObject xwikiObject)
    {
        final EntityReference docRef = xwikiObject.getXClassReference();
        final EntityReference spaceRef = docRef.getParent();
        final String className = JavaIdentifierEscaper.escape(spaceRef.getName())
                                 + "." + JavaIdentifierEscaper.escape(docRef.getName());
        final Class<?> objectClass;
        try {
            objectClass = Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Couldn't find a class called " + className + " perhaps the class "
                                       + "was not converted before trying to convert the object?");
        }

        final Object out;
        try {
            out = objectClass.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Could not instanciate the class " + objectClass.getName());
        }

        final Field[] fields = objectClass.getFields();
        for (final Field field : fields) {
            final BaseProperty xProp =
                (BaseProperty) xwikiObject.getField(JavaIdentifierEscaper.unescape(field.getName()));
            if (xProp != null) {
                try {
                    copyFieldFromXObject(out, field, xProp);
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to populate the persistable object of class "
                                               + objectClass.getName() + " which ought to have all public "
                                               + "fields. Perhaps a class was defined over it?", e);
                }
            }
        }

        return out;
    }

    private static void copyFieldFromXObject(final Object copyTo,
                                             final Field field,
                                             final BaseProperty xProp) throws IllegalAccessException
    {
        if (xProp instanceof NumberProperty) {
            copyNumberFieldFromXObject(copyTo, field, xProp);
            return;
        }

        checkTypeMatch(field, classForProperty(xProp), xProp);
        field.set(copyTo, xProp.getValue());
    }

    private static void copyNumberFieldFromXObject(final Object copyTo,
                                                   final Field field,
                                                   final BaseProperty xProp) throws IllegalAccessException
    {
        // Don't bother if it's null (literal "" is returned)
        if (xProp.toText() == "") {
            return;
        }

        if (xProp instanceof IntegerProperty) {
            checkTypeMatch(field, Integer.class, xProp);
            field.set(copyTo, Integer.valueOf(xProp.toText()));

        } else if (xProp instanceof LongProperty) {
            checkTypeMatch(field, Long.class, xProp);
            field.set(copyTo, Long.valueOf(xProp.toText()));

        } else if (xProp instanceof FloatProperty) {
            checkTypeMatch(field, Float.class, xProp);
            field.set(copyTo, Float.valueOf(xProp.toText()));

        } else if (xProp instanceof DoubleProperty) {
            checkTypeMatch(field, Double.class, xProp);
            field.set(copyTo, Double.valueOf(xProp.toText()));

        } else {
            throw new RuntimeException("Encountered an unhandled property of type " + xProp.getClass());
        }
    }

    /* ------------------------- Write a persistable object into an XObject. ------------------------- */

    private static BaseObject convertToXObject(final Object persistable, final BaseClass xwikiClass)
    {
        final String[] splitClassName = persistable.getClass().getName().split(".");
        final List<String> spaceNames = new ArrayList<String>();
        final String wikiName = JavaIdentifierEscaper.unescape(splitClassName[0]);
        final String pageName = JavaIdentifierEscaper.unescape(splitClassName[splitClassName.length - 1]);
        for (int i = 1; i < splitClassName.length - 1; i++) {
            spaceNames.add(JavaIdentifierEscaper.unescape(splitClassName[i]));
        }
        final EntityReference docRef = new DocumentReference(wikiName, spaceNames, pageName);

        final BaseObject out = new BaseObject();

        final Field[] fields = persistable.getClass().getFields();
        for (final Field field : fields) {

            final PropertyClass propClass =
                (PropertyClass) xwikiClass.get(JavaIdentifierEscaper.unescape(field.getName()));
            if (propClass == null) {
                throw new RuntimeException("The Object of type " + persistable.getClass().getName()
                                           + " contains a field " + field.getName() + " which has no "
                                           + "corrisponding property in the XClass");
            }

            final Object fieldValue;
            try {
                fieldValue = field.get(persistable);
            } catch (IllegalAccessException e) {
                throw new RuntimeException("Failed to collect information the persistable object of class "
                                           + persistable.getClass().getName() + " which ought to have all "
                                           + "public fields. Perhaps a class was defined over it?", e);
            }

            if (fieldValue != null) {
                final BaseProperty prop = propClass.newProperty();
                checkTypeMatch(field, classForProperty(prop), prop);
                prop.setValue(fieldValue);
            }
        }

        return out;
    }

    /* ------------------------- Helper Functions ------------------------- */

    private static Class classForProperty(final BaseProperty prop)
    {
        final Class out;
        if (prop instanceof BaseStringProperty) {
            out = String.class;
        } else if (prop instanceof ListProperty) {
            out = List.class;
        } else if (prop instanceof DateProperty) {
            out = Date.class;
        } else if (prop instanceof IntegerProperty) {
            out = Integer.class;
        } else if (prop instanceof LongProperty) {
            out = Long.class;
        } else if (prop instanceof FloatProperty) {
            out = Float.class;
        } else if (prop instanceof DoubleProperty) {
            out = Double.class;
        } else {
            throw new RuntimeException("Encountered an unhandled property of type " + prop.getClass());
        }
        return out;
    }

    private static void checkTypeMatch(final Field field, final Class<?> expected, final BaseProperty xProp)
    {
        if (field.getType() != expected) {
            throw new RuntimeException("Type mismatch, the XObject property " + xProp.getName()
                                       + " is a " + xProp.getClass().getName() + " which requies a "
                                       + expected.getName() + " field type but the field type is a "
                                       + field.getType().getName());
        }
    }
}
