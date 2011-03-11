/*
 * Copyright 2010 Henry Coles
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); 
 * you may not use this file except in compliance with the License. 
 * You may obtain a copy of the License at 
 * 
 * http://www.apache.org/licenses/LICENSE-2.0 
 * 
 * Unless required by applicable law or agreed to in writing, 
 * software distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and limitations under the License. 
 */

package org.pitest.junit.adapter;

import static org.pitest.util.Unchecked.translateCheckedException;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.junit.runner.Runner;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.manipulation.Filterable;
import org.junit.runner.manipulation.NoTestsRemainException;
import org.junit.runners.model.RunnerBuilder;
import org.pitest.extension.ResultCollector;
import org.pitest.functional.Option;
import org.pitest.internal.ClassLoaderDetectionStrategy;
import org.pitest.internal.IsolationUtils;
import org.pitest.junit.CustomRunnerExecutor;
import org.pitest.junit.ForeignClassLoaderCustomRunnerExecutor;
import org.pitest.junit.PossibilitiesBuilder;
import org.pitest.reflection.Reflection;
import org.pitest.testunit.AbstractTestUnit;

public class AdaptedJUnitTestUnit extends AbstractTestUnit {

  private final ClassLoaderDetectionStrategy loaderDetection;
  private final Class<?>                     clazz;
  private final Option<Filter>               filter;

  public AdaptedJUnitTestUnit(final Class<?> clazz, final Option<Filter> filter) {
    this(IsolationUtils.loaderDetectionStrategy(), clazz, filter);
  }

  public AdaptedJUnitTestUnit(
      final ClassLoaderDetectionStrategy loaderDetection, final Class<?> clazz,
      final Option<Filter> filter) {
    super(new org.pitest.Description(createName(clazz, filter), clazz, null));
    this.loaderDetection = loaderDetection;
    this.clazz = clazz;
    this.filter = filter;
  }

  private static String createName(final Class<?> clazz,
      final Option<Filter> filter) {
    if (filter.hasSome()) {
      return filter.value().describe();
    } else {
      return clazz.getName();
    }
  }

  @Override
  public void execute(final ClassLoader loader, final ResultCollector rc) {
    final Runner runner = createRunner(this.clazz);
    filterIfRequired(rc, runner);

    try {
      if (this.loaderDetection.fromDifferentLoader(runner.getClass(), loader)) {
        executeInDifferentClassLoader(loader, rc, runner);

      } else {
        final CustomRunnerExecutor nativeCe = new CustomRunnerExecutor(
            this.getDescription(), runner, rc);
        nativeCe.run();
      }

    } catch (final Exception e) {
      throw translateCheckedException(e);
    }

  }

  private void filterIfRequired(final ResultCollector rc, final Runner runner) {
    if (this.filter.hasSome()) {
      final Filterable f = (Filterable) runner;
      try {
        f.filter(this.filter.value());
      } catch (final NoTestsRemainException e1) {
        rc.notifySkipped(this.getDescription());
        return;
      }
    }
  }

  public static Runner createRunner(final Class<?> clazz) {
    final RunnerBuilder builder = createRunnerBuilder(clazz);
    try {
      return builder.runnerForClass(clazz);
    } catch (final Throwable ex) {
      ex.printStackTrace();
      throw translateCheckedException(ex);
    }

  }

  private static RunnerBuilder createRunnerBuilder(final Class<?> clazz) {
    return new PossibilitiesBuilder(true);
  }

  private void executeInDifferentClassLoader(final ClassLoader loader,
      final ResultCollector rc, final Runner runner)
      throws IllegalAccessException, InvocationTargetException {

    // must jump through hoops to run in different class loader
    // when even our framework classes may be duplicated
    // translate everything via stirngs
    final List<String> q = new ArrayList<String>(runner.testCount() * 2);
    final ForeignClassLoaderCustomRunnerExecutor ce = new ForeignClassLoaderCustomRunnerExecutor(
        runner);
    Object foreignCe = ce;
    foreignCe = IsolationUtils.cloneForLoader(ce, loader);
    final Method run = Reflection.publicMethod(foreignCe.getClass(), "run");

    // set an uncloned list to receive results in
    final Method set = Reflection
        .publicMethod(foreignCe.getClass(), "setQueue");
    set.invoke(foreignCe, q);
    run.invoke(foreignCe);

    for (final String each : q) {
      final String results[] = each.split(",");
      final String type = results[0];
      if (type.equals("FAIL")) {
        final Throwable t = (Throwable) IsolationUtils
            .fromTransportString(results[2]);
        rc.notifyEnd(this.getDescription(), t);
      } else if (type.equals("IGNORE")) {
        rc.notifySkipped(this.getDescription());

      } else if (type.equals("START")) {
        rc.notifyStart(this.getDescription());

      } else if (type.equals("END")) {
        rc.notifyEnd(this.getDescription());
      }
    }
  }

  @Override
  public String toString() {
    return "AdaptedJUnitTestUnit [clazz=" + this.clazz + ", filter="
        + this.filter + "]";
  }

}