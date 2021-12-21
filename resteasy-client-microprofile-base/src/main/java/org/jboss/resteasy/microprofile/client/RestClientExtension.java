package org.jboss.resteasy.microprofile.client;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.spi.AfterBeanDiscovery;
import javax.enterprise.inject.spi.AfterDeploymentValidation;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.Extension;
import javax.enterprise.inject.spi.ProcessAnnotatedType;
import javax.enterprise.inject.spi.WithAnnotations;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import javax.enterprise.inject.spi.CDI;

public class RestClientExtension implements Extension {

    private Set<Class<?>> proxyTypes = new LinkedHashSet<>();

    private Set<Throwable> errors = new LinkedHashSet<>();

    public void registerRestClient(@Observes
                                   @WithAnnotations(RegisterRestClient.class) ProcessAnnotatedType<?> type) {
        Class<?> javaClass = type.getAnnotatedType().getJavaClass();
        if (javaClass.isInterface()) {
            proxyTypes.add(javaClass);
        } else {
            errors.add(new IllegalArgumentException("Rest client needs to be an interface " + javaClass));
        }
    }

    private Optional<String> extractBaseUri(RegisterRestClient annotation) {
        String baseUri = annotation.baseUri();
        return Optional.ofNullable("".equals(baseUri) ? null : baseUri);
    }

    private Optional<String> extractConfigKey(RegisterRestClient annotation) {
        String configKey = annotation.configKey();
        return Optional.ofNullable("".equals(configKey) ? null : configKey);
    }

    public void createProxy(@Observes AfterBeanDiscovery afterBeanDiscovery, BeanManager beanManager) {
        for (Class<?> proxyType : proxyTypes) {
            for (AnnotatedType<?> annotatedType : afterBeanDiscovery.getAnnotatedTypes(proxyType)) {
                RegisterRestClient annotation = annotatedType.getAnnotation(RegisterRestClient.class);
                if (annotation == null) {
                    continue; // Another extension may have removed the annotation
                }
                Optional<String> maybeUri = extractBaseUri(annotation);
                Optional<String> maybeConfigKey = extractConfigKey(annotation);
                afterBeanDiscovery.addBean(new RestClientDelegateBean(proxyType, annotatedType, beanManager, maybeUri, maybeConfigKey));
            }
        }
    }

    public void reportErrors(@Observes AfterDeploymentValidation afterDeploymentValidation) {
        for (Throwable error : errors) {
            afterDeploymentValidation.addDeploymentProblem(error);
        }
    }

    /**
     *
     * @deprecated this method is not supported and will eventually be deleted
     * @return {@code true} if CDI is believed to be activated, otherwise {@code false}
     */
    @Deprecated
    public static boolean isCDIActive() {
        try {
            return CDI.current().getBeanManager() != null;
        } catch (IllegalStateException ise) {
            // This happens when a CDIProvider is not available.
            return false;
        }
    }

    /**
     * This method currently does nothing.
     *
     * @deprecated this method is not supported and will eventually be deleted
     */
    @Deprecated
    public static void clearBeanManager() {
        // nothing to do
    }

    /**
     * Lifted from CdiConstructorInjector in resteasy-cdi
     * @deprecated this method is not supported and will eventually be deleted
     */
    @Deprecated
    public static Object construct(Class<?> clazz){
        BeanManager manager;
        try {
            manager = CDI.current().getBeanManager();
        } catch (IllegalStateException ignore) {
            return null;
        }
        if (manager != null) {
            Set<Bean<?>> beans = manager.getBeans(clazz);
            if (beans.isEmpty()) {
                return null;
            }

            if (beans.size() > 1) {
                Set<Bean<?>> modifiableBeans = new HashSet<>();
                modifiableBeans.addAll(beans);
                // Ambiguous dependency may occur if a resource has subclasses
                // Therefore we remove those beans
                for (Iterator<Bean<?>> iterator = modifiableBeans.iterator(); iterator.hasNext();){
                    Bean<?> bean = iterator.next();
                    if (!bean.getBeanClass().equals(clazz) && !bean.isAlternative()){
                        // remove Beans that have clazz in their type closure but not as a base class
                        iterator.remove();
                    }
                }
                beans = modifiableBeans;
            }
            Bean<?> bean = manager.resolve(beans);
            if (bean == null) {
                return null;
            }
            CreationalContext<?> context = manager.createCreationalContext(bean);
            if (context == null) {
                return null;
            }
            return manager.getReference(bean, clazz, context);
        }else {
            // CDI is not active.
            return null;
        }
    }
}
