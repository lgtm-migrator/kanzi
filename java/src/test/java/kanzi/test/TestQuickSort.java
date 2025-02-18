/*
Copyright 2011-2022 Frederic Langlet
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
you may obtain a copy of the License at

                http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package kanzi.test;

import kanzi.util.sort.QuickSort;
import org.junit.Assert;
import org.junit.Test;


public class TestQuickSort extends TestAbstractSort
{
    @Test
    public void testQuickSort()
    {
        Assert.assertTrue(testCorrectness("QuickSort", new QuickSort(), 20));
        testSpeed("QuickSort", new QuickSort(), 5000);
    }
}