Component({
  properties: { item: Object },
  data: { shortName: '商品' },
  observers: { item(value) { this.setData({ shortName: value && value.name ? value.name.substring(0, 2) : '商品' }) } },
  methods: {
    detail() { this.triggerEvent('detail', { id: this.data.item.id }) },
    add() { this.triggerEvent('add', { id: this.data.item.id }) }
  }
})
