package org.springdoc.core;

import com.fasterxml.jackson.annotation.JsonView;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.RequestInfo.ParameterType;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.http.HttpMethod;
import org.springframework.util.CollectionUtils;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.SessionStatus;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.util.UriComponentsBuilder;

import javax.validation.constraints.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springdoc.core.Constants.*;

public abstract class AbstractRequestBuilder {

    // using string litterals to support both validation-api v1 and v2
    private static final String[] ANNOTATIONS_FOR_REQUIRED = {NotNull.class.getName(), "javax.validation.constraints.NotBlank", "javax.validation.constraints.NotEmpty"};
    private static final String POSITIVE_OR_ZERO = "javax.validation.constraints.PositiveOrZero";
    private static final String NEGATIVE_OR_ZERO = "javax.validation.constraints.NegativeOrZero";
    private final AbstractParameterBuilder parameterBuilder;
    private final RequestBodyBuilder requestBodyBuilder;
    private final OperationBuilder operationBuilder;
    protected static final List<Class> PARAM_TYPES_TO_IGNORE = new ArrayList<>();

    static {
        PARAM_TYPES_TO_IGNORE.add(WebRequest.class);
        PARAM_TYPES_TO_IGNORE.add(NativeWebRequest.class);
        PARAM_TYPES_TO_IGNORE.add(java.security.Principal.class);
        PARAM_TYPES_TO_IGNORE.add(HttpMethod.class);
        PARAM_TYPES_TO_IGNORE.add(java.util.Locale.class);
        PARAM_TYPES_TO_IGNORE.add(java.util.TimeZone.class);
        PARAM_TYPES_TO_IGNORE.add(java.io.InputStream.class);
        PARAM_TYPES_TO_IGNORE.add(java.time.ZoneId.class);
        PARAM_TYPES_TO_IGNORE.add(java.io.Reader.class);
        PARAM_TYPES_TO_IGNORE.add(java.io.OutputStream.class);
        PARAM_TYPES_TO_IGNORE.add(java.io.Writer.class);
        PARAM_TYPES_TO_IGNORE.add(java.util.Map.class);
        PARAM_TYPES_TO_IGNORE.add(org.springframework.ui.Model.class);
        PARAM_TYPES_TO_IGNORE.add(org.springframework.ui.ModelMap.class);
        PARAM_TYPES_TO_IGNORE.add(Errors.class);
        PARAM_TYPES_TO_IGNORE.add(BindingResult.class);
        PARAM_TYPES_TO_IGNORE.add(SessionStatus.class);
        PARAM_TYPES_TO_IGNORE.add(UriComponentsBuilder.class);
        PARAM_TYPES_TO_IGNORE.add(RequestAttribute.class);
    }

    protected AbstractRequestBuilder(AbstractParameterBuilder parameterBuilder, RequestBodyBuilder requestBodyBuilder,
                                     OperationBuilder operationBuilder) {
        super();
        this.parameterBuilder = parameterBuilder;
        this.requestBodyBuilder = requestBodyBuilder;
        this.operationBuilder = operationBuilder;
    }

    protected boolean isParamTypeToIgnore(Class<?> paramType) {
        return false;
    }

    public Operation build(Components components, HandlerMethod handlerMethod, RequestMethod requestMethod,
                           Operation operation, MethodAttributes methodAttributes) {
        // Documentation
        String operationId = operationBuilder.getOperationId(handlerMethod.getMethod().getName(),
                operation.getOperationId());

        operation.setOperationId(operationId);
        // requests
        LocalVariableTableParameterNameDiscoverer d = parameterBuilder.getLocalSpringDocParameterNameDiscoverer();
        String[] pNames = d.getParameterNames(handlerMethod.getMethod());
        java.lang.reflect.Parameter[] parameters = handlerMethod.getMethod().getParameters();
        String[] reflectionParametersNames = Arrays.stream(parameters).map(java.lang.reflect.Parameter::getName).toArray(String[]::new);
        if (pNames == null) {
            pNames = reflectionParametersNames;
        }
        RequestBodyInfo requestBodyInfo = new RequestBodyInfo(methodAttributes);
        List<Parameter> operationParameters = (operation.getParameters() != null) ? operation.getParameters()
                : new ArrayList<>();
        Map<String, io.swagger.v3.oas.annotations.Parameter> parametersDocMap = getApiParameters(handlerMethod.getMethod());

        for (int i = 0; i < pNames.length; i++) {
            // check if query param
            Parameter parameter = null;
            final String pName = pNames[i] == null ? reflectionParametersNames[i] : pNames[i];
            io.swagger.v3.oas.annotations.Parameter parameterDoc = parameterBuilder.getParameterAnnotation(
                    handlerMethod, parameters[i], i, io.swagger.v3.oas.annotations.Parameter.class);
            if (parameterDoc == null) {
                parameterDoc = parametersDocMap.get(pName);
            }
            // use documentation as reference
            if (parameterDoc != null) {
                if (parameterDoc.hidden()) {
                    continue;
                }
                parameter = parameterBuilder.buildParameterFromDoc(parameterDoc, null,
                        methodAttributes.getJsonViewAnnotation());
            }

            if (!isParamToIgnore(parameters[i])) {
                ParameterInfo parameterInfo = new ParameterInfo(pName, parameters[i], parameter, i);
                parameter = buildParams(parameterInfo, components, handlerMethod, requestMethod,
                        methodAttributes.getJsonViewAnnotation());
                // Merge with the operation parameters
                parameter = parameterBuilder.mergeParameter(operationParameters, parameter);
                if (isValidParameter(parameter)) {
                    applyBeanValidatorAnnotations(parameter, Arrays.asList(parameters[i].getAnnotations()));
                } else if (!RequestMethod.GET.equals(requestMethod)) {
                    requestBodyInfo.incrementNbParam();
                    requestBodyInfo.setRequestBody(operation.getRequestBody());
                    requestBodyBuilder.calculateRequestBodyInfo(components, handlerMethod, methodAttributes, i,
                            parameterInfo, requestBodyInfo);
                }
            }
        }

        LinkedHashMap<String, Parameter> map = operationParameters.stream()
                .collect(Collectors.toMap(
                        Parameter::getName,
                        parameter -> parameter,
                        (u, v) -> {
                            throw new IllegalStateException(String.format("Duplicate key %s", u));
                        },
                        LinkedHashMap::new
                ));

        for (Map.Entry<String, io.swagger.v3.oas.annotations.Parameter> entry : parametersDocMap.entrySet()) {
            if (entry.getKey() != null && !map.containsKey(entry.getKey()) && !entry.getValue().hidden()) {
                //Convert
                Parameter parameter = parameterBuilder.buildParameterFromDoc(entry.getValue(), components,
                        methodAttributes.getJsonViewAnnotation());
                map.put(entry.getKey(), parameter);
            }
        }

        setParams(operation, new ArrayList(map.values()) , requestBodyInfo);
        // allow for customisation
        operation = customiseOperation(operation, handlerMethod);

        return operation;
    }

    protected abstract Operation customiseOperation(Operation operation, HandlerMethod handlerMethod);

    protected boolean isParamToIgnore(java.lang.reflect.Parameter parameter) {
        if (parameter.isAnnotationPresent(PathVariable.class)) {
            return false;
        }
        return parameterBuilder.isAnnotationToIgnore(parameter) || isParamTypeToIgnore(parameter.getType()) || PARAM_TYPES_TO_IGNORE.contains(parameter.getType()) || (AnnotationUtils.findAnnotation(parameter.getType(), Hidden.class) != null);
    }

    private void setParams(Operation operation, List<Parameter> operationParameters, RequestBodyInfo requestBodyInfo) {
        if (!CollectionUtils.isEmpty(operationParameters)) {
            operation.setParameters(operationParameters);
        }
        if (requestBodyInfo.getRequestBody() != null)
            operation.setRequestBody(requestBodyInfo.getRequestBody());
    }

    private boolean isValidParameter(Parameter parameter) {
        return parameter != null && (parameter.getName() != null || parameter.get$ref() != null);
    }

    private Parameter buildParams(ParameterInfo parameterInfo, Components components, HandlerMethod handlerMethod,
                                  RequestMethod requestMethod, JsonView jsonView) {
        java.lang.reflect.Parameter parameters = parameterInfo.getParameter();
        int index = parameterInfo.getIndex();

        RequestHeader requestHeader = parameterBuilder.getParameterAnnotation(handlerMethod, parameters, index,
                RequestHeader.class);
        RequestParam requestParam = parameterBuilder.getParameterAnnotation(handlerMethod, parameters, index,
                RequestParam.class);
        PathVariable pathVar = parameterBuilder.getParameterAnnotation(handlerMethod, parameters, index,
                PathVariable.class);

        Parameter parameter = null;
        RequestInfo requestInfo;

        if (requestHeader != null) {
            requestInfo = new RequestInfo(ParameterType.HEADER_PARAM, requestHeader.value(), requestHeader.required(),
                    requestHeader.defaultValue());
            parameter = buildParam(parameterInfo, components, requestInfo, jsonView);

        } else if (requestParam != null) {
            requestInfo = new RequestInfo(ParameterType.QUERY_PARAM, requestParam.value(), requestParam.required(),
                    requestParam.defaultValue());
            parameter = buildParam(parameterInfo, components, requestInfo, jsonView);
        } else if (pathVar != null) {
            String pName = parameterInfo.getpName();
            String name = StringUtils.isBlank(pathVar.value()) ? pName : pathVar.value();
            parameterInfo.setpName(name);
            // check if PATH PARAM
            requestInfo = new RequestInfo(ParameterType.PATH_PARAM, pathVar.value(), Boolean.TRUE, null);
            parameter = buildParam(parameterInfo, components, requestInfo, jsonView);
        }

        // By default
        if (RequestMethod.GET.equals(requestMethod)) {
            parameter = this.buildParam(QUERY_PARAM, components, parameterInfo, Boolean.TRUE, null, jsonView);
        }
        return parameter;
    }

    private Parameter buildParam(ParameterInfo parameterInfo, Components components, RequestInfo requestInfo,
                                 JsonView jsonView) {
        Parameter parameter;
        String pName = parameterInfo.getpName();
        String name = StringUtils.isBlank(requestInfo.value()) ? pName : requestInfo.value();
        parameterInfo.setpName(name);

        if (!ValueConstants.DEFAULT_NONE.equals(requestInfo.defaultValue()))
            parameter = this.buildParam(requestInfo.type(), components, parameterInfo, false,
                    requestInfo.defaultValue(), jsonView);
        else
            parameter = this.buildParam(requestInfo.type(), components, parameterInfo, requestInfo.required(), null,
                    jsonView);
        return parameter;
    }

    private Parameter buildParam(String in, Components components, ParameterInfo parameterInfo, Boolean required,
                                 String defaultValue, JsonView jsonView) {
        Parameter parameter = parameterInfo.getParameterModel();
        String name = parameterInfo.getpName();

        if (parameter == null) {
            parameter = new Parameter();
            parameterInfo.setParameterModel(parameter);
        }

        if (StringUtils.isBlank(parameter.getName())) {
            parameter.setName(name);
        }

        if (StringUtils.isBlank(parameter.getIn())) {
            parameter.setIn(in);
        }

        if (required != null && parameter.getRequired() == null) {
            parameter.setRequired(required);
        }

        if (parameter.getSchema() == null) {
            Schema<?> schema = parameterBuilder.calculateSchema(components, parameterInfo.getParameter(), name, null,
                    jsonView);
            if (defaultValue != null)
                schema.setDefault(defaultValue);
            parameter.setSchema(schema);
        }
        return parameter;
    }


    private void applyBeanValidatorAnnotations(final Parameter parameter, final List<Annotation> annotations) {
        Map<String, Annotation> annos = new HashMap<>();
        if (annotations != null) {
            annotations.forEach(annotation -> annos.put(annotation.annotationType().getName(), annotation));
        }

        boolean annotationExists = Arrays.stream(ANNOTATIONS_FOR_REQUIRED).anyMatch(annos::containsKey);

        if (annotationExists) {
            parameter.setRequired(true);
        }

        Schema<?> schema = parameter.getSchema();

        if (annos.containsKey(Min.class.getName())) {
            Min min = (Min) annos.get(Min.class.getName());
            schema.setMinimum(BigDecimal.valueOf(min.value()));
        }
        if (annos.containsKey(Max.class.getName())) {
            Max max = (Max) annos.get(Max.class.getName());
            schema.setMaximum(BigDecimal.valueOf(max.value()));
        }
        calculateSize(annos, schema);
        if (annos.containsKey(DecimalMin.class.getName())) {
            DecimalMin min = (DecimalMin) annos.get(DecimalMin.class.getName());
            if (min.inclusive()) {
                schema.setMinimum(BigDecimal.valueOf(Double.parseDouble(min.value())));
            } else {
                schema.setExclusiveMinimum(!min.inclusive());
            }
        }
        if (annos.containsKey(DecimalMax.class.getName())) {
            DecimalMax max = (DecimalMax) annos.get(DecimalMax.class.getName());
            if (max.inclusive()) {
                schema.setMaximum(BigDecimal.valueOf(Double.parseDouble(max.value())));
            } else {
                schema.setExclusiveMaximum(!max.inclusive());
            }
        }
        if (annos.containsKey(POSITIVE_OR_ZERO)) {
            schema.setMinimum(BigDecimal.ZERO);
        }
        if (annos.containsKey(NEGATIVE_OR_ZERO)) {
            schema.setMaximum(BigDecimal.ZERO);
        }
        if (annos.containsKey(Pattern.class.getName())) {
            Pattern pattern = (Pattern) annos.get(Pattern.class.getName());
            schema.setPattern(pattern.regexp());
        }
    }

    private void calculateSize(Map<String, Annotation> annos, Schema<?> schema) {
        if (annos.containsKey(Size.class.getName())) {
            Size size = (Size) annos.get(Size.class.getName());
            if (OPENAPI_ARRAY_TYPE.equals(schema.getType())) {
                schema.setMinItems(size.min());
                schema.setMaxItems(size.max());
            } else if (OPENAPI_STRING_TYPE.equals(schema.getType())) {
                schema.setMinLength(size.min());
                schema.setMaxLength(size.max());
            }
        }
    }

    public RequestBodyBuilder getRequestBodyBuilder() {
        return requestBodyBuilder;
    }

    private Map<String, io.swagger.v3.oas.annotations.Parameter> getApiParameters(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();

        Set<io.swagger.v3.oas.annotations.Parameters> apiParametersDoc = AnnotatedElementUtils
                .findAllMergedAnnotations(method, io.swagger.v3.oas.annotations.Parameters.class);
        Map<String, io.swagger.v3.oas.annotations.Parameter> apiParametersMap = apiParametersDoc.stream()
                .flatMap(x -> Stream.of(x.value())).collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x));

        Set<io.swagger.v3.oas.annotations.Parameters> apiParametersDocDeclaringClass = AnnotatedElementUtils
                .findAllMergedAnnotations(declaringClass, io.swagger.v3.oas.annotations.Parameters.class);
        apiParametersMap.putAll(apiParametersDocDeclaringClass.stream()
                .flatMap(x -> Stream.of(x.value())).collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x)));

        Set<io.swagger.v3.oas.annotations.Parameter> apiParameterDoc = AnnotatedElementUtils
                .findAllMergedAnnotations(method, io.swagger.v3.oas.annotations.Parameter.class);
        apiParametersMap.putAll(apiParameterDoc.stream().collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x)));

        Set<io.swagger.v3.oas.annotations.Parameter> apiParameterDocDeclaringClass = AnnotatedElementUtils
                .findAllMergedAnnotations(declaringClass, io.swagger.v3.oas.annotations.Parameter.class);
        apiParametersMap.putAll(apiParameterDocDeclaringClass.stream().collect(Collectors.toMap(io.swagger.v3.oas.annotations.Parameter::name, x -> x)));

        return apiParametersMap;
    }
}
