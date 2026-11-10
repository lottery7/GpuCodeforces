package com.example.gpuplatform.sync.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SlugsTest {

    @Test
    fun `deriveSlug returns the directory name unchanged`() {
        assertThat(Slugs.deriveSlug("vector-addition")).isEqualTo("vector-addition")
        assertThat(Slugs.deriveSlug("softmax")).isEqualTo("softmax")
    }

    @Test
    fun `deriveSlug trims whitespace`() {
        assertThat(Slugs.deriveSlug("  vector-addition ")).isEqualTo("vector-addition")
    }

    @Test
    fun `deriveSlug rejects blank`() {
        assertThatThrownBy { Slugs.deriveSlug("   ") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `deriveSlug rejects invalid slugs with uppercase or spaces`() {
        assertThatThrownBy { Slugs.deriveSlug("Vector Addition") }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { Slugs.deriveSlug("vector--addition") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}