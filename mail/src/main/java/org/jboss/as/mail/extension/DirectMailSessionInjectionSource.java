/*
 *
 *  JBoss, Home of Professional Open Source.
 *  Copyright 2013, Red Hat, Inc., and individual contributors
 *  as indicated by the @author tags. See the copyright.txt file in the
 *  distribution for a full listing of individual contributors.
 *
 *  This is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as
 *  published by the Free Software Foundation; either version 2.1 of
 *  the License, or (at your option) any later version.
 *
 *  This software is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 *  Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this software; if not, write to the Free
 *  Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 *  02110-1301 USA, or see the FSF site: http://www.fsf.org.
 * /
 */

package org.jboss.as.mail.extension;


import org.jboss.as.ee.component.Attachments;
import org.jboss.as.ee.component.EEModuleDescription;
import org.jboss.as.ee.component.InjectionSource;
import org.jboss.as.naming.ManagedReferenceFactory;
import org.jboss.as.naming.ServiceBasedNamingStore;
import org.jboss.as.naming.deployment.ContextNames;
import org.jboss.as.naming.service.BinderService;
import org.jboss.as.server.deployment.DeploymentPhaseContext;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.msc.inject.Injector;
import org.jboss.msc.service.AbstractServiceListener;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;


/**
 * A binding description for DataSourceDefinition annotations.
 * <p/>
 * The referenced datasource must be directly visible to the
 * component declaring the annotation.
 *
 * @author Tomaz Cerar
 */
class DirectMailSessionInjectionSource extends InjectionSource {

    private final String jndiName;
    private final SessionProvider provider;


    public DirectMailSessionInjectionSource(final String jndiName, final SessionProvider provider) {
        this.jndiName = jndiName;
        this.provider = provider;
    }

    public void getResourceValue(final ResolutionContext context, final ServiceBuilder<?> serviceBuilder, final DeploymentPhaseContext phaseContext, final Injector<ManagedReferenceFactory> injector) throws DeploymentUnitProcessingException {
        final DeploymentUnit deploymentUnit = phaseContext.getDeploymentUnit();
        final EEModuleDescription eeModuleDescription = deploymentUnit.getAttachment(Attachments.EE_MODULE_DESCRIPTION);

        try {
            MailSessionService mailSessionService = new DirectMailSessionService(provider);
            startDataSource(mailSessionService, jndiName, eeModuleDescription, context, phaseContext.getServiceTarget(), serviceBuilder, injector);

        } catch (Exception e) {
            throw new DeploymentUnitProcessingException(e);
        }
    }

    private void startDataSource(final MailSessionService mailSessionService,
                                 final String jndiName,
                                 final EEModuleDescription moduleDescription,
                                 final ResolutionContext context,
                                 final ServiceTarget serviceTarget,
                                 final ServiceBuilder valueSourceServiceBuilder, final Injector<ManagedReferenceFactory> injector) {


        final ServiceName dataSourceServiceName = MailSessionAdd.MAIL_SESSION_SERVICE_NAME.append("MailSessionDefinition", moduleDescription.getApplicationName(), moduleDescription.getModuleName(), jndiName);
        final ServiceBuilder<?> dataSourceServiceBuilder = serviceTarget
                .addService(dataSourceServiceName, mailSessionService);

        final ContextNames.BindInfo bindInfo = ContextNames.bindInfoForEnvEntry(context.getApplicationName(), context.getModuleName(), context.getComponentName(), !context.isCompUsesModule(), jndiName);

        final MailSessionManagedReferenceFactory referenceFactoryService = new MailSessionManagedReferenceFactory(mailSessionService);

        final BinderService binderService = new BinderService(bindInfo.getBindName(), this);
        final ServiceBuilder<?> binderBuilder = serviceTarget
                .addService(bindInfo.getBinderServiceName(), binderService)
                .addInjection(binderService.getManagedObjectInjector(), referenceFactoryService)
                .addDependency(bindInfo.getParentContextServiceName(), ServiceBasedNamingStore.class, binderService.getNamingStoreInjector()).addListener(new AbstractServiceListener<Object>() {
                    public void transition(final ServiceController<? extends Object> controller, final ServiceController.Transition transition) {
                        switch (transition) {
                            case STARTING_to_UP: {
                                MailLogger.ROOT_LOGGER.boundMailSession(jndiName);
                                break;
                            }
                            case START_REQUESTED_to_DOWN: {
                                MailLogger.ROOT_LOGGER.unboundMailSession(jndiName);
                                break;
                            }
                            case REMOVING_to_REMOVED: {
                                MailLogger.ROOT_LOGGER.debugf("Removed Mail Session [%s]", jndiName);
                                break;
                            }
                        }
                    }
                });

        dataSourceServiceBuilder.setInitialMode(ServiceController.Mode.ACTIVE).install();
        binderBuilder.setInitialMode(ServiceController.Mode.ACTIVE).install();

        valueSourceServiceBuilder.addDependency(bindInfo.getBinderServiceName(), ManagedReferenceFactory.class, injector);
    }


}
