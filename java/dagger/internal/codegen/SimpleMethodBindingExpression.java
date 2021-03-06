/*
 * Copyright (C) 2016 The Dagger Authors.
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

package dagger.internal.codegen;

import static com.google.auto.common.MoreElements.asExecutable;
import static com.google.common.base.Preconditions.checkArgument;
import static dagger.internal.codegen.Accessibility.isTypeAccessibleFrom;
import static dagger.internal.codegen.CodeBlocks.toParametersCodeBlock;
import static dagger.internal.codegen.ContributionBinding.Kind.INJECTION;
import static dagger.internal.codegen.FactoryGenerator.checkNotNullProvidesMethod;
import static dagger.internal.codegen.InjectionMethods.ProvisionMethod.requiresInjectionMethod;
import static dagger.internal.codegen.TypeNames.rawTypeName;

import com.google.auto.common.MoreTypes;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeName;
import dagger.internal.codegen.InjectionMethods.ProvisionMethod;
import java.util.Optional;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

/**
 * A binding expression that invokes methods or constructors directly for a provision binding when
 * possible.
 */
final class SimpleMethodBindingExpression extends SimpleInvocationBindingExpression {
  private final CompilerOptions compilerOptions;
  private final ProvisionBinding provisionBinding;
  private final ComponentBindingExpressions componentBindingExpressions;
  private final GeneratedComponentModel generatedComponentModel;
  private final ComponentRequirementFields componentRequirementFields;
  private final Elements elements;

  SimpleMethodBindingExpression(
      CompilerOptions compilerOptions,
      ProvisionBinding provisionBinding,
      BindingExpression delegate,
      ComponentBindingExpressions componentBindingExpressions,
      GeneratedComponentModel generatedComponentModel,
      ComponentRequirementFields componentRequirementFields,
      DaggerTypes types,
      Elements elements) {
    super(delegate, types);
    checkArgument(
        provisionBinding.implicitDependencies().isEmpty(),
        "framework deps are not currently supported");
    checkArgument(provisionBinding.bindingElement().isPresent());
    this.compilerOptions = compilerOptions;
    this.provisionBinding = provisionBinding;
    this.componentBindingExpressions = componentBindingExpressions;
    this.generatedComponentModel = generatedComponentModel;
    this.componentRequirementFields = componentRequirementFields;
    this.elements = elements;
  }

  @Override
  Expression getInstanceDependencyExpression(
      DependencyRequest.Kind requestKind, ClassName requestingClass) {
    return requiresInjectionMethod(provisionBinding, requestingClass.packageName())
        ? invokeInjectionMethod(requestingClass)
        : invokeMethod(requestingClass);
  }

  private Expression invokeMethod(ClassName requestingClass) {
    // TODO(dpb): align this with the contents of InlineMethods.create
    CodeBlock arguments =
        provisionBinding
            .dependencies()
            .stream()
            .map(request -> dependencyArgument(request, requestingClass))
            .collect(toParametersCodeBlock());
    ExecutableElement method = asExecutable(provisionBinding.bindingElement().get());
    CodeBlock invocation;
    switch (method.getKind()) {
      case CONSTRUCTOR:
        invocation = CodeBlock.of("new $T($L)", constructorTypeName(requestingClass), arguments);
        break;
      case METHOD:
        CodeBlock module =
            moduleReference(requestingClass)
                .orElse(CodeBlock.of("$T", provisionBinding.bindingTypeElement().get()));
        invocation = maybeCheckForNulls(
            CodeBlock.of("$L.$L($L)", module, method.getSimpleName(), arguments));
        break;
      default:
        throw new IllegalStateException();
    }
    return Expression.create(provisionBinding.key().type(), invocation);
  }

  private TypeName constructorTypeName(ClassName requestingClass) {
    DeclaredType type = MoreTypes.asDeclared(provisionBinding.key().type());
    TypeName typeName = TypeName.get(type);
    if (type.getTypeArguments()
        .stream()
        .allMatch(t -> isTypeAccessibleFrom(t, requestingClass.packageName()))) {
      return typeName;
    }
    return rawTypeName(typeName);
  }

  private Expression invokeInjectionMethod(ClassName requestingClass) {
    return injectMembers(
        maybeCheckForNulls(
            ProvisionMethod.invoke(
                provisionBinding,
                request -> dependencyArgument(request, requestingClass),
                requestingClass,
                moduleReference(requestingClass))));
  }

  private CodeBlock dependencyArgument(DependencyRequest dependency, ClassName requestingClass) {
    return componentBindingExpressions
        .getDependencyArgumentExpression(dependency, requestingClass)
        .codeBlock();
  }

  private CodeBlock maybeCheckForNulls(CodeBlock methodCall) {
    return !provisionBinding.bindingKind().equals(INJECTION)
            && provisionBinding.shouldCheckForNull(compilerOptions)
        ? checkNotNullProvidesMethod(methodCall)
        : methodCall;
  }

  private Expression injectMembers(CodeBlock instance) {
    if (provisionBinding.injectionSites().isEmpty()) {
      return Expression.create(provisionBinding.key().type(), instance);
    }
    // Java 7 type inference can't figure out that instance in
    // injectParameterized(Parameterized_Factory.newParameterized()) is Parameterized<T> and not
    // Parameterized<Object>
    if (!MoreTypes.asDeclared(provisionBinding.key().type()).getTypeArguments().isEmpty()) {
      TypeName keyType = TypeName.get(provisionBinding.key().type());
      instance = CodeBlock.of("($T) ($T) $L", keyType, rawTypeName(keyType), instance);
    }

    MethodSpec membersInjectionMethod =
        generatedComponentModel.getMembersInjectionMethod(provisionBinding.key());
    TypeMirror returnType =
        membersInjectionMethod.returnType.equals(TypeName.OBJECT)
            ? elements.getTypeElement(Object.class.getCanonicalName()).asType()
            : provisionBinding.key().type();
    return Expression.create(returnType, CodeBlock.of("$N($L)", membersInjectionMethod, instance));
  }

  private Optional<CodeBlock> moduleReference(ClassName requestingClass) {
    return provisionBinding.requiresModuleInstance()
        ? provisionBinding
            .contributingModule()
            .map(Element::asType)
            .map(ComponentRequirement::forModule)
            .map(
                requirement ->
                    componentRequirementFields.getExpression(requirement, requestingClass))
        : Optional.empty();
  }
}
