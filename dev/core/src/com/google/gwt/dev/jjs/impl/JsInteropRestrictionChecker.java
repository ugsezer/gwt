/*
 * Copyright 2015 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.gwt.dev.jjs.impl;

import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.dev.MinimalRebuildCache;
import com.google.gwt.dev.jjs.ast.Context;
import com.google.gwt.dev.jjs.ast.JConstructor;
import com.google.gwt.dev.jjs.ast.JDeclaredType;
import com.google.gwt.dev.jjs.ast.JExpressionStatement;
import com.google.gwt.dev.jjs.ast.JField;
import com.google.gwt.dev.jjs.ast.JInterfaceType;
import com.google.gwt.dev.jjs.ast.JMember;
import com.google.gwt.dev.jjs.ast.JMethod;
import com.google.gwt.dev.jjs.ast.JMethod.JsPropertyType;
import com.google.gwt.dev.jjs.ast.JMethodCall;
import com.google.gwt.dev.jjs.ast.JPrimitiveType;
import com.google.gwt.dev.jjs.ast.JProgram;
import com.google.gwt.dev.jjs.ast.JStatement;
import com.google.gwt.dev.jjs.ast.JType;
import com.google.gwt.dev.jjs.ast.JVisitor;
import com.google.gwt.thirdparty.guava.common.base.Predicate;
import com.google.gwt.thirdparty.guava.common.collect.FluentIterable;
import com.google.gwt.thirdparty.guava.common.collect.Iterables;
import com.google.gwt.thirdparty.guava.common.collect.Maps;
import com.google.gwt.thirdparty.guava.common.collect.Sets;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks and throws errors for invalid JsInterop constructs.
 */
// TODO: handle custom JsType field/method names when that feature exists.
// TODO: move JsInterop checks from JSORestrictionsChecker to here.
// TODO: provide more information in global name collisions as it could be difficult to pinpoint in
// big projects.
public class JsInteropRestrictionChecker extends JVisitor {

  public static void exec(TreeLogger logger, JProgram jprogram,
      MinimalRebuildCache minimalRebuildCache) throws UnableToCompleteException {
    JsInteropRestrictionChecker jsInteropRestrictionChecker =
        new JsInteropRestrictionChecker(logger, jprogram, minimalRebuildCache);
    jsInteropRestrictionChecker.accept(jprogram);
    if (jsInteropRestrictionChecker.hasErrors) {
      throw new UnableToCompleteException();
    }
  }

  private Map<String, String> currentJsTypeMethodNameByGetterNames;
  private Map<String, String> currentJsTypeMethodNameByMemberNames;
  private Map<String, String> currentJsTypeMethodNameBySetterNames;
  private Set<JMethod> currentJsTypeProcessedMethods;
  private Map<String, JType> currentJsTypePropertyTypeByName;
  private JDeclaredType currentType;
  private boolean hasErrors;
  private final JProgram jprogram;
  private final TreeLogger logger;
  private final MinimalRebuildCache minimalRebuildCache;

  public JsInteropRestrictionChecker(TreeLogger logger, JProgram jprogram,
      MinimalRebuildCache minimalRebuildCache) {
    this.logger = logger;
    this.jprogram = jprogram;
    this.minimalRebuildCache = minimalRebuildCache;
  }

  @Override
  public void endVisit(JDeclaredType x, Context ctx) {
    assert currentType == x;
    currentType = null;
  }

  @Override
  public boolean visit(JDeclaredType x, Context ctx) {
    assert currentType == null;
    currentJsTypeProcessedMethods = Sets.newHashSet();
    currentJsTypePropertyTypeByName = Maps.newHashMap();
    currentJsTypeMethodNameByMemberNames = Maps.newHashMap();
    currentJsTypeMethodNameByGetterNames = Maps.newHashMap();
    currentJsTypeMethodNameBySetterNames = Maps.newHashMap();
    minimalRebuildCache.removeJsInteropNames(x.getName());
    currentType = x;

    checkJsFunctionHierarchy(x);

    if (currentType instanceof JInterfaceType) {
      checkJsTypeHierarchy((JInterfaceType) currentType);
    } else {
      checkConstructors(x);
    }

    // Perform custom class traversal to examine fields and methods of this class and all
    // superclasses so that name collisions between local and inherited members can be found.
    do {
      acceptWithInsertRemoveImmutable(x.getFields());
      acceptWithInsertRemoveImmutable(x.getMethods());
      x = x.getSuperClass();
    } while (x != null);

    // Skip the default class traversal.
    return false;
  }

  private void checkConstructors(JDeclaredType x) {
    List<JMethod> exportedCtors = FluentIterable
        .from(x.getMethods())
        .filter(new Predicate<JMethod>() {
           @Override
           public boolean apply(JMethod m) {
             return m.isExported() && m instanceof JConstructor;
           }
        }).toList();

    if (exportedCtors.isEmpty()) {
      return;
    }

    if (exportedCtors.size() > 1) {
      logError("More than one constructor exported for %s.", x.getName());
    }

    final JConstructor exportedCtor = (JConstructor) exportedCtors.get(0);
    if (!exportedCtor.getExportName().isEmpty()) {
      logError("Constructor '%s' cannot have an export name.", exportedCtor.getQualifiedName());
    }

    boolean anyNonDelegatingConstructor = Iterables.any(x.getMethods(), new Predicate<JMethod>() {
      @Override
      public boolean apply(JMethod method) {
        return method != exportedCtor && method instanceof JConstructor
            && !isDelegatingToConstructor((JConstructor) method, exportedCtor);
      }
    });

    if (anyNonDelegatingConstructor) {
      logError("Constructor '%s' can only be exported if all constructors in the class are "
          + "delegating to it.", exportedCtor.getQualifiedName());
    }
  }

  private boolean isDelegatingToConstructor(JConstructor ctor, JConstructor targetCtor) {
    List<JStatement> statements = ctor.getBody().getBlock().getStatements();
    JExpressionStatement statement = (JExpressionStatement) statements.get(0);
    JMethodCall call = (JMethodCall) statement.getExpr();
    assert call.isStaticDispatchOnly() : "Every ctor should either have this() or super() call";
    return call.getTarget().equals(targetCtor);
  }

  @Override
  public boolean visit(JField x, Context ctx) {
    if (currentType == x.getEnclosingType() && x.isExported()) {
      checkExportName(x);
    } else if (x.isJsTypeMember()) {
      checkJsTypeFieldName(x, x.getJsMemberName());
    }

    return false;
  }

  @Override
  public boolean visit(JMethod x, Context ctx) {
    if (!currentJsTypeProcessedMethods.add(x)) {
      return false;
    }
    currentJsTypeProcessedMethods.addAll(x.getOverriddenMethods());

    if (currentType == x.getEnclosingType() && x.isExported()) {
      checkExportName(x);
    } else if (x.isOrOverridesJsTypeMethod()) {
      checkJsTypeMethod(x);
    }

    if (currentType == x.getEnclosingType()) {
      if (x.isJsPropertyAccessor() && !currentType.isJsType()) {
        if (currentType instanceof JInterfaceType) {
          logError("Method '%s' can't be a JsProperty since interface '%s' is not a JsType.",
              x.getName(), x.getEnclosingType().getName());
        } else {
          logError("Method '%s' can't be a JsProperty since '%s' "
              + "is not an interface.", x.getName(), x.getEnclosingType().getName());
        }
      }
    }

    return false;
  }

  private void checkExportName(JMember x) {
    boolean success = minimalRebuildCache.addExportedGlobalName(x.getQualifiedExportName(),
        currentType.getName());
    if (!success) {
      logError("Member '%s' can't be exported because the global name '%s' is already taken.",
          x.getQualifiedName(), x.getQualifiedExportName());
    }
  }

  private void checkInconsistentPropertyType(String propertyName, String enclosingTypeName,
      JType parameterType) {
    JType recordedType = currentJsTypePropertyTypeByName.put(propertyName, parameterType);
    if (recordedType != null && recordedType != parameterType) {
      logError("The setter and getter for JsProperty '%s' in type '%s' must have consistent types.",
          propertyName, enclosingTypeName);
    }
  }

  private void checkJsTypeHierarchy(JInterfaceType interfaceType) {
    if (currentType.isJsType()) {
      for (JDeclaredType superInterface : interfaceType.getImplements()) {
        if (!superInterface.isJsType()) {
          logWarning(
              "JsType interface '%s' extends non-JsType interface '%s'. This is not recommended.",
              interfaceType.getName(), superInterface.getName());
        }
      }
    }
  }

  private void checkJsTypeFieldName(JField field, String memberName) {
    boolean success =
        currentJsTypeMethodNameByMemberNames.put(memberName, field.getQualifiedName()) == null;
    if (!success) {
      logError("Field '%s' can't be exported in type '%s' because the member name "
          + "'%s' is already taken.", field.getQualifiedName(), currentType.getName(), memberName);
    }
  }

  private void checkJsTypeMethod(JMethod method) {
    if (method.isSynthetic() && !method.isForwarding()) {
      // A name slot taken up by a synthetic method, such as a bridge method for a generic method,
      // is not the fault of the user and so should not be reported as an error. JS generation
      // should take responsibility for ensuring that only the correct method version (in this
      // particular set of colliding method names) is exported. Forwarding synthetic methods
      // (such as an accidental override forwarding method that occurs when a JsType interface
      // starts exposing a method in class B that is only ever implemented in its parent class A)
      // though should be checked since they are exported and do take up an name slot.
      return;
    }

    String jsMemberName = method.getImmediateOrTransitiveJsMemberName();
    String qualifiedMethodName = method.getQualifiedName();
    String typeName = method.getEnclosingType().getName();
    JsPropertyType jsPropertyType = method.getImmediateOrTransitiveJsPropertyType();

    if (jsMemberName == null) {
      logError("'%s' can't be exported because the method overloads multiple methods with "
          + "different names.", qualifiedMethodName);
    }

    if (jsPropertyType == JsPropertyType.GET) {
      if (!method.getParams().isEmpty() || method.getType() == JPrimitiveType.VOID) {
        logError("There can't be void return type or any parameters for the JsProperty getter"
            + " '%s'.", qualifiedMethodName);
        return;
      }
      if (method.getType() != JPrimitiveType.BOOLEAN && method.getName().startsWith("is")) {
        logError("There can't be non-booelean return for the JsProperty 'is' getter '%s'.",
            qualifiedMethodName);
        return;
      }
      if (currentJsTypeMethodNameByGetterNames.put(jsMemberName, qualifiedMethodName) != null) {
        // Don't allow multiple getters for the same property name.
        logError("There can't be more than one getter for JsProperty '%s' in type '%s'.",
            jsMemberName, typeName);
        return;
      }
      checkNameCollisionForGetterAndRegular(jsMemberName, typeName);
      checkInconsistentPropertyType(jsMemberName, typeName, method.getOriginalReturnType());
    } else if (jsPropertyType == JsPropertyType.SET) {
      if (method.getParams().size() != 1 || method.getType() != JPrimitiveType.VOID) {
        logError("There needs to be single parameter and void return type for the JsProperty setter"
            + " '%s'.", qualifiedMethodName);
        return;
      }
      if (currentJsTypeMethodNameBySetterNames.put(jsMemberName, qualifiedMethodName) != null) {
        // Don't allow multiple setters for the same property name.
        logError("There can't be more than one setter for JsProperty '%s' in type '%s'.",
            jsMemberName, typeName);
        return;
      }
      checkNameCollisionForSetterAndRegular(jsMemberName, typeName);
      checkInconsistentPropertyType(jsMemberName, typeName,
          Iterables.getOnlyElement(method.getParams()).getType());
    } else if (jsPropertyType == JsPropertyType.UNDEFINED) {
      // We couldn't extract the JsPropertyType.
      logError("JsProperty '%s' doesn't follow Java Bean naming conventions.", qualifiedMethodName);
    } else {
      // If it's just an regular JsType method.
      if (currentJsTypeMethodNameByMemberNames.put(jsMemberName, qualifiedMethodName) != null) {
        logError("Method '%s' can't be exported in type '%s' because the member name "
            + "'%s' is already taken.", qualifiedMethodName, currentType.getName(), jsMemberName);
      }
      checkNameCollisionForGetterAndRegular(jsMemberName, typeName);
      checkNameCollisionForSetterAndRegular(jsMemberName, typeName);
    }
  }

  private void checkNameCollisionForGetterAndRegular(String getterName, String typeName) {
    if (currentJsTypeMethodNameByGetterNames.containsKey(getterName)
        && currentJsTypeMethodNameByMemberNames.containsKey(getterName)) {
      logError("The JsType member '%s' and JsProperty '%s' can't both be named "
          + "'%s' in type '%s'.", currentJsTypeMethodNameByMemberNames.get(getterName),
          currentJsTypeMethodNameByGetterNames.get(getterName), getterName, typeName);
    }
  }

  private void checkNameCollisionForSetterAndRegular(String setterName, String typeName) {
    if (currentJsTypeMethodNameBySetterNames.containsKey(setterName)
        && currentJsTypeMethodNameByMemberNames.containsKey(setterName)) {
      logError("The JsType member '%s' and JsProperty '%s' can't both be named "
          + "'%s' in type '%s'.", currentJsTypeMethodNameByMemberNames.get(setterName),
          currentJsTypeMethodNameBySetterNames.get(setterName), setterName, typeName);
    }
  }

  private void checkJsFunctionHierarchy(JDeclaredType type) {
    if (!type.isOrExtendsJsFunction()) {
      return;
    }

    List<JInterfaceType> implementedInterfaces = type.getImplements();

    if (type.isJsFunction()) {
      if (implementedInterfaces.size() > 0) {
        logError("JsFunction '%s' cannot extend other interfaces.", type);
      }
      if (type.isJsType()) {
        logError("'%s' cannot be both a JsFunction and a JsType at the same time.", type);
      }
      return;
    }

    if (type instanceof JInterfaceType) {
      logError("Interface '%s' cannot extend a JsFunction interface.", type);
      return;
    }

    if (implementedInterfaces.size() != 1) {
      logError("JsFunction implementation '%s' cannot implement more than one interface.", type);
    }

    if (type.isJsType()) {
      logError("'%s' cannot be both a JsFunction implementation and a JsType at the same time.",
          type);
    }

    if (type.getSuperClass() != jprogram.getTypeJavaLangObject()) {
      logError("JsFunction implementation '%s' cannot extend a class.", type);
    }
  }

  private void logError(String format, JType type) {
    logError(format, type.getName());
  }

  private void logError(String format, Object... args) {
    logger.log(TreeLogger.ERROR, String.format(format, args));
    hasErrors = true;
  }

  private void logWarning(String format, Object... args) {
    logger.log(TreeLogger.WARN, String.format(format, args));
  }
}
