package com.leeduc.platechecker

data class TollRow(
    val stationName: String,
    val loai1: String,
    val loai2: String,
    val loai3: String,
    val loai4: String,
    val loai5: String
)

data class TollSection(
    val title: String,
    val rows: List<TollRow>
)

object PriceData {

    val sections: List<TollSection> = listOf(
        TollSection(
            title = "BẢNG GIÁ THEO LOẠI XE",
            rows = listOf(
                TollRow("QL8A (Hồng Lĩnh, km479)", "163.000", "239.000", "318.000", "396.000", "630.000"),
                TollRow("ĐT548 (Đồng Lộc, Can Lộc)", "149.000", "218.520", "290.666", "361.000", "575.000"),
                TollRow("ĐT550 (Thạch Hà - TP Hà Tĩnh)", "135.000", "197.000", "262.000", "326.000", "519.000"),
                TollRow("Cẩm Quan, Cẩm Xuyên", "118.000", "172.000", "229.000", "285.000", "454.000"),
                TollRow("Kỳ Trung, Kỳ Anh", "92.000", "134.000", "178.000", "222.000", "353.000"),
                TollRow("QL12C (Kỳ Tân, Kỳ Anh)", "80.000", "116.000", "155.000", "193.000", "307.000"),
                TollRow("Tiến Châu Văn Hóa", "52.000", "75.000", "100.000", "125.000", "198.000"),
                TollRow("12A (Ba Đồn)", "45.000", "65.000", "87.000", "108.000", "172.000"),
                TollRow("Cự Nẫm", "26.000", "37.000", "50.000", "62.000", "99.000"),
                TollRow("Nông Trường", "10.000", "14.000", "19.000", "24.000", "38.000")
            )
        ),
        TollSection(
            title = "NHẬT LỆ",
            rows = listOf(
                TollRow("9B (Vạn Ninh)", "18.000", "27.000", "35.000", "44.000", "70.000"),
                TollRow("9C (Cuối Lệ Thuỷ)", "32.000", "48.000", "63.000", "79.000", "126.000"),
                TollRow("9D (Vĩnh Linh - Bến Quan)", "53.000", "79.000", "104.000", "131.000", "207.000"),
                TollRow("ĐT75 (Cồn Tiên - Gio Linh)", "66.000", "99.000", "130.000", "164.000", "259.000"),
                TollRow("9A (Đông Hà - cam lộ, km740)", "78.000", "117.000", "154.000", "194.000", "307.000")
            )
        )
    )
}
