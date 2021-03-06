package com.creater.process;

import android.content.Context;

import com.creater.annotation.NewView;
import com.google.auto.service.AutoService;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;

/**
 * Created by luwei on 18-6-7.
 */
@AutoService(Processor.class)
public class ViewProcesser extends AbstractProcessor {

    private Filer mFiler;
    private static Messager mMessager;
    public static Types typeUtils = null;
    private Elements mElementUtils;
    private RoundEnvironment mRoundEnv = null;
    private Map<String, List<Element>> elementMap = new HashMap<>();
    private HashMap<String,Element> fieldNameMaps = new HashMap<>();
    private Set<Element> processedElement = new HashSet<>();
    private static boolean hasProcess = false;
    private static final String METHOD_NAME = "doNewView";
    public static final String TARGET_NAME = "target";
    public static final String VIEW_NAME = "v";

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        mFiler = processingEnvironment.getFiler();
        mMessager = processingEnvironment.getMessager();
        mElementUtils = processingEnvironment.getElementUtils();
        typeUtils = processingEnvironment.getTypeUtils();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        annotations.add(NewView.class.getCanonicalName());
        return annotations;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> set, RoundEnvironment roundEnvironment) {
        long start = System.currentTimeMillis();
        if (hasProcess)
            return true;
        hasProcess = true;
        this.mRoundEnv = roundEnvironment;
        Set<? extends Element> bindViewElements = roundEnvironment.getElementsAnnotatedWith(NewView.class);
        for (Element element : bindViewElements) {
            //获取完成类名
            if (!(element instanceof VariableElement))
                continue;
            TypeElement enclosingElement = (TypeElement) element.getEnclosingElement();
            String enclosingName = enclosingElement.getQualifiedName().toString();
            if (elementMap.get(enclosingName) == null)
                elementMap.put(enclosingName, new ArrayList<Element>());
            elementMap.get(enclosingName).add(element);
        }
        Set<Map.Entry<String, List<Element>>> entries = elementMap.entrySet();
        for (Map.Entry<String, List<Element>> entry : entries) {
            String clazz = entry.getKey();
            String pkg = clazz.substring(0, clazz.lastIndexOf("."));
            String name = clazz.substring(clazz.lastIndexOf(".") + 1, clazz.length());
            List<Element> elementList = entry.getValue();
            ClassName target = ClassName.get((TypeElement) elementList.get(0).getEnclosingElement());
            TypeSpec.Builder clazzTypeBuilder = TypeSpec.classBuilder(name + "_NewView")
                    .addModifiers(Modifier.PUBLIC, Modifier.FINAL);
            List<String> methods = new ArrayList<>();

            for (Element element : elementList) {
                NewView v = element.getAnnotation(NewView.class);
                VariableElement bindViewElement = (VariableElement) element;
                String fieldName = bindViewElement.getSimpleName().toString();
                fieldNameMaps.put(fieldName,element);
            }
            for (Element element : elementList) {
                generateMethod(target, clazzTypeBuilder, methods, element);
            }

            MethodSpec.Builder spec = MethodSpec.methodBuilder(METHOD_NAME)
                    .addModifiers(Modifier.PUBLIC,Modifier.STATIC)
                    .returns(void.class)
                    .addParameter(Context.class, "ctx")
                    .addParameter(target,TARGET_NAME);
            for (String method : methods) {
                spec.addStatement(method + "(ctx," + TARGET_NAME + ")");
            }
            clazzTypeBuilder.addMethod(spec.build());
            JavaFile javaFile = JavaFile.builder(pkg, clazzTypeBuilder.build())
                    .build();
            try {
                javaFile.toJavaFileObject().delete();
                javaFile.writeTo(mFiler);
                note("generate class " + pkg + "." + name + "_NewView");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        note("ViewCreater process annotation cost " + (System.currentTimeMillis() - start) + "ms");
        return false;
    }

    private void generateMethod(ClassName target, TypeSpec.Builder clazzTypeBuilder, List<String> methods, Element element) {
        if (processedElement.contains(element))
            return;
        processedElement.add(element);
        //因为BindView只作用于filed，所以这里可直接进行强转
        VariableElement bindViewElement = (VariableElement) element;
        //3.获取注解的成员变量名
        String fieldName = bindViewElement.getSimpleName().toString();
        //3.获取注解的成员变量类型
        String classType = bindViewElement.asType().toString();
        //4.获取注解元数据
        String methodName = "add" + fieldName;

        NewView v = element.getAnnotation(NewView.class);
        if (!isEmpty(v.parent()) && !v.parent().equals("this")){
            if (!processedElement.contains(fieldNameMaps.get(v.parent()))){
                generateMethod(target,clazzTypeBuilder,methods, fieldNameMaps.get(v.parent()));
            }
        }

        methods.add(methodName);
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(methodName)
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class)
                .addParameter(Context.class, "ctx")
                .addParameter(target, TARGET_NAME);


        CodeBlock codeBlock = new CreateProcesser().generateCodeBlock(element);
        methodBuilder.addCode(codeBlock);

        codeBlock = new ListenerProcesser().generateCodeBlock(element);
        methodBuilder.addCode(codeBlock);

        codeBlock = new ParentProcesser().generateCodeBlock(element);
        methodBuilder.addCode(codeBlock);

        codeBlock = new TextViewProcesser().generateCodeBlock(element);
        methodBuilder.addCode(codeBlock);

        codeBlock = new ImageViewProcesser().generateCodeBlock(element);
        methodBuilder.addCode(codeBlock);

        methodBuilder.addStatement(TARGET_NAME + ".$L = " + VIEW_NAME, fieldName);
        clazzTypeBuilder.addMethod(methodBuilder.build());
    }

    public static void note(String msg) {
        mMessager.printMessage(Diagnostic.Kind.NOTE, msg);
    }

    public static void note(String format, Object... args) {
        mMessager.printMessage(Diagnostic.Kind.NOTE, String.format(format, args));
    }

    public static boolean isEmpty(String s){
        if (s == null || s.equals(""))
            return true;
        return false;
    }

}