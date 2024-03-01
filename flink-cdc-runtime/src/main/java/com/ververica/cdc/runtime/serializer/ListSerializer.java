/*
 * Copyright 2023 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.runtime.serializer;

import org.apache.flink.api.common.typeutils.CompositeTypeSerializerSnapshot;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.common.typeutils.TypeSerializerSnapshot;
import org.apache.flink.core.memory.DataInputView;
import org.apache.flink.core.memory.DataOutputView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.ververica.cdc.common.utils.Preconditions.checkNotNull;

/**
 * A serializer for {@link List Lists}. The serializer relies on an element serializer for the
 * serialization of the list's elements.
 *
 * <p>The serialization format for the list is as follows: four bytes for the length of the lost,
 * followed by the serialized representation of each element.
 *
 * @param <T> The type of element in the list.
 */
public final class ListSerializer<T> extends TypeSerializer<List<T>> {

    private static final long serialVersionUID = 1119562170939152304L;

    /** The serializer for the elements of the list. */
    private final TypeSerializer<T> elementSerializer;

    /**
     * Creates a list serializer that uses the given serializer to serialize the list's elements.
     *
     * @param elementSerializer The serializer for the elements of the list
     */
    public ListSerializer(TypeSerializer<T> elementSerializer) {
        this.elementSerializer = checkNotNull(elementSerializer);
    }

    // ------------------------------------------------------------------------
    //  ListSerializer specific properties
    // ------------------------------------------------------------------------

    /**
     * Gets the serializer for the elements of the list.
     *
     * @return The serializer for the elements of the list
     */
    public TypeSerializer<T> getElementSerializer() {
        return elementSerializer;
    }

    // ------------------------------------------------------------------------
    //  Type Serializer implementation
    // ------------------------------------------------------------------------

    @Override
    public boolean isImmutableType() {
        return false;
    }

    @Override
    public TypeSerializer<List<T>> duplicate() {
        TypeSerializer<T> duplicateElement = elementSerializer.duplicate();
        return duplicateElement == elementSerializer
                ? this
                : new ListSerializer<>(duplicateElement);
    }

    @Override
    public List<T> createInstance() {
        return new ArrayList<>(0);
    }

    @Override
    public List<T> copy(List<T> from) {
        List<T> newList = new ArrayList<>(from.size());

        // We iterate here rather than accessing by index, because we cannot be sure that
        // the given list supports RandomAccess.
        // The Iterator should be stack allocated on new JVMs (due to escape analysis)
        for (T element : from) {
            newList.add(elementSerializer.copy(element));
        }
        return newList;
    }

    @Override
    public List<T> copy(List<T> from, List<T> reuse) {
        return copy(from);
    }

    @Override
    public int getLength() {
        return -1; // var length
    }

    @Override
    public void serialize(List<T> list, DataOutputView target) throws IOException {
        final int size = list.size();
        target.writeInt(size);

        // We iterate here rather than accessing by index, because we cannot be sure that
        // the given list supports RandomAccess.
        // The Iterator should be stack allocated on new JVMs (due to escape analysis)
        for (T element : list) {
            elementSerializer.serialize(element, target);
        }
    }

    @Override
    public List<T> deserialize(DataInputView source) throws IOException {
        final int size = source.readInt();
        // create new list with (size + 1) capacity to prevent expensive growth when a single
        // element is added
        final List<T> list = new ArrayList<>(size + 1);
        for (int i = 0; i < size; i++) {
            list.add(elementSerializer.deserialize(source));
        }
        return list;
    }

    @Override
    public List<T> deserialize(List<T> reuse, DataInputView source) throws IOException {
        return deserialize(source);
    }

    @Override
    public void copy(DataInputView source, DataOutputView target) throws IOException {
        // copy number of elements
        final int num = source.readInt();
        target.writeInt(num);
        for (int i = 0; i < num; i++) {
            elementSerializer.copy(source, target);
        }
    }

    // --------------------------------------------------------------------

    @Override
    public boolean equals(Object obj) {
        return obj == this
                || (obj != null
                        && obj.getClass() == getClass()
                        && elementSerializer.equals(((ListSerializer<?>) obj).elementSerializer));
    }

    @Override
    public int hashCode() {
        return elementSerializer.hashCode();
    }

    // --------------------------------------------------------------------------------------------
    // Serializer configuration snapshot & compatibility
    // --------------------------------------------------------------------------------------------

    @Override
    public TypeSerializerSnapshot<List<T>> snapshotConfiguration() {
        return new ListSerializerSnapshot<>(this);
    }

    /** Snapshot class for the {@link ListSerializer}. */
    public static final class ListSerializerSnapshot<T>
            extends CompositeTypeSerializerSnapshot<List<T>, ListSerializer<T>> {

        private static final int CURRENT_VERSION = 1;

        /** Constructor for read instantiation. */
        public ListSerializerSnapshot() {
            super(ListSerializer.class);
        }

        /** Constructor to create the snapshot for writing. */
        public ListSerializerSnapshot(ListSerializer<T> listSerializer) {
            super(listSerializer);
        }

        @Override
        public int getCurrentOuterSnapshotVersion() {
            return CURRENT_VERSION;
        }

        @Override
        protected ListSerializer<T> createOuterSerializerWithNestedSerializers(
                TypeSerializer<?>[] nestedSerializers) {
            @SuppressWarnings("unchecked")
            TypeSerializer<T> elementSerializer = (TypeSerializer<T>) nestedSerializers[0];
            return new ListSerializer<>(elementSerializer);
        }

        @Override
        protected TypeSerializer<?>[] getNestedSerializers(ListSerializer<T> outerSerializer) {
            return new TypeSerializer<?>[] {outerSerializer.getElementSerializer()};
        }
    }
}
